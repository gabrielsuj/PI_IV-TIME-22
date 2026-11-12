// Confere a coerência da carga de demonstração (database/seed) além dos validadores:
// referências, jornada, bloqueios, sobreposições, histórico e logins.
//
// Uso (container no ar):
//   docker exec -i consultafacil-mongo mongosh consultafacil_demo --quiet < scripts/verificar-carga.js
const FUSO = 180 * 60000;
let falhas = 0;
function confere(nome, problemas) {
  if (problemas.length === 0) print("OK      " + nome);
  else { falhas++; print("FALHOU  " + nome + " (" + problemas.length + ")"); problemas.slice(0, 5).forEach((p) => print("          - " + p)); }
}

const porId = (colecao) => new Map(db.getCollection(colecao).find().toArray().map((d) => [d._id.toHexString(), d]));
const medicos = porId("medicos"), pacientes = porId("pacientes"), especialidades = porId("especialidades");
const agendas = new Map(db.agendas.find().toArray().map((a) => [a.medicoId.toHexString(), a]));
const bloqueios = db.bloqueios.find().toArray();
const consultas = db.consultas.find().sort({ inicio: 1 }).toArray();
const ATIVAS = ["AGENDADA", "CONFIRMADA"];

print("== Totais ==");
db.getCollectionNames().sort().forEach((c) => print("  " + c.padEnd(16) + db.getCollection(c).countDocuments()));
print("  consultas por status: " + db.consultas.aggregate([{ $group: { _id: "$status", n: { $sum: 1 } } }, { $sort: { _id: 1 } }])
  .toArray().map((s) => s._id + " " + s.n).join(" | "));

print("\n== Coerência ==");
confere("consultas apontam para paciente, médico e especialidade existentes", consultas.filter((c) =>
  !pacientes.has(c.pacienteId.toHexString()) || !medicos.has(c.medicoId.toHexString()) || !especialidades.has(c.especialidadeId.toHexString()))
  .map((c) => c._id.toHexString()));

confere("especialidade da consulta é uma das do médico", consultas.filter((c) => {
  const m = medicos.get(c.medicoId.toHexString());
  return m && !m.especialidadeIds.some((e) => e.equals(c.especialidadeId));
}).map((c) => c._id.toHexString()));

confere("consultas dentro da jornada e da duração da agenda", consultas.filter((c) => {
  const a = agendas.get(c.medicoId.toHexString());
  if (!a) return true;
  const local = new Date(c.inicio.getTime() - FUSO);
  const dia = local.getUTCDay() === 0 ? 7 : local.getUTCDay();
  const minuto = local.getUTCHours() * 60 + local.getUTCMinutes();
  const duracao = Number(a.duracaoMinutos);
  if (c.fim.getTime() - c.inicio.getTime() !== duracao * 60000) return true;
  const j = a.jornadas.find((x) => Number(x.diaSemana) === dia);
  return !j || !j.faixas.some((f) => minuto >= Number(f.inicioMinuto) && minuto + duracao <= Number(f.fimMinuto)
    && (minuto - Number(f.inicioMinuto)) % duracao === 0);
}).map((c) => c._id.toHexString() + " " + c.inicio.toISOString()));

confere("nenhuma consulta dentro de um bloqueio do médico", consultas.filter((c) => bloqueios.some((b) =>
  b.medicoId.equals(c.medicoId) && b.inicio < c.fim && b.fim > c.inicio)).map((c) => c._id.toHexString()));

function sobrepostas(campo) {
  const problemas = [], ultimaPorChave = new Map();
  consultas.filter((c) => ATIVAS.includes(c.status)).forEach((c) => {
    const chave = c[campo].toHexString(), anterior = ultimaPorChave.get(chave);
    if (anterior && anterior.fim > c.inicio) problemas.push(anterior._id.toHexString() + " x " + c._id.toHexString());
    if (!anterior || c.fim > anterior.fim) ultimaPorChave.set(chave, c);
  });
  return problemas;
}
confere("médico sem consultas ativas sobrepostas", sobrepostas("medicoId"));
confere("paciente sem consultas ativas sobrepostas", sobrepostas("pacienteId"));

const eventosPorConsulta = new Map();
db.historico.find().sort({ consultaId: 1, sequencia: 1 }).forEach((e) => {
  const k = e.consultaId.toHexString();
  if (!eventosPorConsulta.has(k)) eventosPorConsulta.set(k, []);
  eventosPorConsulta.get(k).push(e);
});
confere("histórico completo: sequência 1..n, começa em CRIACAO, termina no status atual, versao = n", consultas.filter((c) => {
  const ev = eventosPorConsulta.get(c._id.toHexString()) || [];
  return ev.length === 0 || Number(c.versao) !== ev.length || ev[0].evento !== "CRIACAO"
    || ev.some((e, i) => Number(e.sequencia) !== i + 1 || (i > 0 && e.statusAnterior !== ev[i - 1].statusNovo)
      || (i > 0 && e.ocorridoEm < ev[i - 1].ocorridoEm))
    || ev[ev.length - 1].statusNovo !== c.status || (c.eventosPendentes || []).length > 0;
}).map((c) => c._id.toHexString()));

confere("histórico sem eventos de consultas inexistentes", [...eventosPorConsulta.keys()]
  .filter((k) => !consultas.some((c) => c._id.toHexString() === k)));

const usuarios = db.usuarios.find().toArray();
confere("todo médico ativo com agenda tem login vinculado", [...medicos.values()]
  .filter((m) => m.ativo && agendas.has(m._id.toHexString()) && !m.usuarioId).map((m) => m.nome));
confere("login de paciente tem cadastro de paciente com o mesmo e-mail e CPF", usuarios
  .filter((u) => u.perfil === "PACIENTE" && ![...pacientes.values()].some((p) => p.email === u.email && p.cpf === u.cpf))
  .map((u) => u.email));

print("\n== Próximos 7 dias (ativas) por médico ==");
const agora = new Date(), semana = new Date(Date.now() + 7 * 86400000);
db.consultas.aggregate([
  { $match: { status: { $in: ATIVAS }, inicio: { $gte: agora, $lt: semana } } },
  { $group: { _id: "$medicoId", n: { $sum: 1 } } }
]).toArray().sort((a, b) => b.n - a.n)
  .forEach((g) => print("  " + medicos.get(g._id.toHexString()).nome.padEnd(26) + g.n));

print("\n" + (falhas === 0 ? "Carga coerente: todas as verificações passaram." : falhas + " verificação(ões) falharam."));
