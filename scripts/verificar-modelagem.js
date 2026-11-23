/*
Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

const ana = ObjectId("100000000000000000000001");

let falhas = 0;
function confere(nome, obtido, esperado) {
  if (obtido !== esperado) falhas++;
  print(obtido === esperado ? "OK     " : "FALHOU ", nome, "-> obtido:", obtido, "| esperado:", esperado);
}
function deveRecusar(nome, codigo, acao) {
  try { acao(); falhas++; print("FALHOU ", nome, "-> o banco ACEITOU, deveria recusar"); }
  catch (e) { confere(nome + " (código)", e.code, codigo); }
}
function usaIndice(plano, nome) {
  if (!plano || typeof plano !== "object") return false;
  if (plano.indexName === nome) return true;
  return Object.entries(plano).some(([chave, valor]) => chave !== "rejectedPlans" && usaIndice(valor, nome));
}

// ---------- Positivos: os dados certos aparecem ----------
// Valores da carga de demonstração (database/seed): 11 médicos, Ana e Rafael ativos em Cardiologia.
confere("total de médicos", db.medicos.countDocuments(), 11);
confere("médicos ativos de cardiologia", db.medicos.countDocuments(
  { especialidadeIds: ObjectId("000000000000000000000002"), ativo: true }), 2);

const manha26 = { medicoId: ana,
  inicio: { $lt: new Date("2026-10-26T15:00:00Z") },   // antes de 12h SP
  fim:    { $gt: new Date("2026-10-26T11:00:00Z") } }; // depois de 08h SP
confere("bloqueios da Ana na manhã de 26/10", db.bloqueios.countDocuments(manha26), 1);
confere("essa busca usa o índice", usaIndice(db.bloqueios.find(manha26).explain().queryPlanner.winningPlan,
  "ix_bloqueio_medico_periodo"), true);

// ---------- Negativos: dados errados são recusados ----------
deveRecusar("bloqueio com fim antes do início", 121, () => db.bloqueios.insertOne({
  medicoId: ana, inicio: new Date("2026-10-26T13:00:00Z"), fim: new Date("2026-10-26T12:00:00Z") }));

deveRecusar("médico com CRM repetido", 11000, () => db.medicos.insertOne({
  nome: "Teste", crmNumero: "000123", crmUf: "SP",
  especialidadeIds: [ObjectId("000000000000000000000001")], ativo: true }));

deveRecusar("médico sem especialidade", 121, () => db.medicos.insertOne({
  nome: "Teste", crmNumero: "999999", crmUf: "SP", especialidadeIds: [], ativo: true }));

deveRecusar("segunda agenda para a Ana", 11000, () => db.agendas.insertOne({
  medicoId: ana, fusoHorario: "America/Sao_Paulo", duracaoMinutos: 30, jornadas: [] }));

deveRecusar("agenda com dia 8 e faixa invertida", 121, () => db.agendas.insertOne({
  medicoId: ObjectId(), fusoHorario: "America/Sao_Paulo", duracaoMinutos: 30,
  jornadas: [{ diaSemana: 8, faixas: [{ inicioMinuto: 720, fimMinuto: 480 }] }] }));

deveRecusar("especialidade com código repetido", 11000, () => db.especialidades.insertOne({
  codigo: "cardiologia", nome: "Outra", ativa: true }));

print(falhas === 0 ? "Todas as verificações passaram." : falhas + " verificação(ões) falharam.");
quit(falhas > 0 ? 1 : 0);
