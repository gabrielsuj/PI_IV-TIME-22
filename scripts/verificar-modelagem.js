const ana = ObjectId("100000000000000000000001");

function confere(nome, obtido, esperado) {
  print(obtido === esperado ? "OK     " : "FALHOU ", nome, "-> obtido:", obtido, "| esperado:", esperado);
}
function deveRecusar(nome, acao) {
  try { acao(); print("FALHOU ", nome, "-> o banco ACEITOU, deveria recusar"); }
  catch (e) { print("OK     ", nome, "-> recusado:", e.code, e.codeName || e.errmsg || ""); }
}

// ---------- Positivos: os dados certos aparecem ----------
confere("total de médicos", db.medicos.countDocuments(), 2);
confere("médicos ativos de cardiologia", db.medicos.countDocuments(
  { especialidadeIds: ObjectId("000000000000000000000002"), ativo: true }), 1);

const manha26 = { medicoId: ana,
  inicio: { $lt: new Date("2026-10-26T15:00:00Z") },   // antes de 12h SP
  fim:    { $gt: new Date("2026-10-26T11:00:00Z") } }; // depois de 08h SP
confere("bloqueios da Ana na manhã de 26/10", db.bloqueios.countDocuments(manha26), 1);
confere("essa busca usa o índice", JSON.stringify(db.bloqueios.find(manha26).explain())
  .includes("ix_bloqueio_medico_periodo"), true);

// ---------- Negativos: dados errados são recusados ----------
deveRecusar("bloqueio com fim antes do início", () => db.bloqueios.insertOne({
  medicoId: ana, inicio: new Date("2026-10-26T13:00:00Z"), fim: new Date("2026-10-26T12:00:00Z") }));

deveRecusar("médico com CRM repetido", () => db.medicos.insertOne({
  nome: "Teste", crmNumero: "000123", crmUf: "SP",
  especialidadeIds: [ObjectId("000000000000000000000001")], ativo: true }));

deveRecusar("médico sem especialidade", () => db.medicos.insertOne({
  nome: "Teste", crmNumero: "999999", crmUf: "SP", especialidadeIds: [], ativo: true }));

deveRecusar("segunda agenda para a Ana", () => db.agendas.insertOne({
  medicoId: ana, fusoHorario: "America/Sao_Paulo", duracaoMinutos: 30, jornadas: [] }));

deveRecusar("agenda com dia 8 e faixa invertida", () => db.agendas.insertOne({
  medicoId: ObjectId(), fusoHorario: "America/Sao_Paulo", duracaoMinutos: 30,
  jornadas: [{ diaSemana: 8, faixas: [{ inicioMinuto: 720, fimMinuto: 480 }] }] }));

deveRecusar("especialidade com código repetido", () => db.especialidades.insertOne({
  codigo: "cardiologia", nome: "Outra", ativa: true }));
