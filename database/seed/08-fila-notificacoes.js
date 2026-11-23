/*
Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

// A secretaria cancela uma consulta futura e os dois pacientes da fila disputam a vaga.
const interessados = db.pacientes.find({
  email: { $in: ["carla.souza@consultafacil.demo", "diego.santos@consultafacil.demo"] }
}).sort({ email: 1 }).toArray();
if (interessados.length !== 2) throw new Error("Carregue os pacientes da demonstração.");
const ids = interessados.map(p => p._id);
const ativas = { $in: ["AGENDADA", "CONFIRMADA"] };
const agora = new Date();
const amanha = new Date(agora.getTime() + 86400000);
const candidatas = db.consultas.find({
  _id: { $gte: ObjectId("700000000000000000000000"), $lt: ObjectId("710000000000000000000000") },
  pacienteId: { $nin: ids }, status: ativas, inicio: { $gte: amanha }
}).sort({ inicio: 1, _id: 1 });
let vaga;
while (candidatas.hasNext()) {
  const c = candidatas.next();
  if (!db.consultas.findOne({ pacienteId: { $in: ids }, status: ativas,
    inicio: { $lt: c.fim }, fim: { $gt: c.inicio } })) {
    vaga = c;
    break;
  }
}
if (!vaga) throw new Error("A carga precisa de uma consulta futura compatível com Carla e Diego.");

interessados.forEach((p, i) => {
  const _id = ObjectId(i === 0 ? "810000000000000000000001" : "810000000000000000000002");
  db.filaEspera.replaceOne({ _id }, {
    _id, pacienteId: p._id, medicoId: vaga.medicoId, especialidadeId: vaga.especialidadeId,
    turnoPreferido: "QUALQUER", status: "AGUARDANDO", ultimaSinalizacao: null,
    validoAte: vaga.fim, consultaAgendadaId: null, atendidaEm: null,
    registradoPor: { perfil: "PACIENTE", id: p.usuarioId },
    criadoEm: new Date(agora.getTime() - (2 - i) * 60000), atualizadoEm: agora
  }, { upsert: true });
});

// Exemplo de notificação interna, ligada a um cancelamento real no histórico da carga.
const cancelamento = db.historico.findOne({ pacienteId: ObjectId("400000000000000000000001"),
  evento: "CANCELAMENTO" }, {}, { sort: { ocorridoEm: -1 } });
if (!cancelamento) throw new Error("A carga precisa do cancelamento de demonstração de Paulo.");
const medico = db.medicos.findOne({ _id: cancelamento.medicoId });
const avisoId = ObjectId("820000000000000000000001");
db.notificacoes.replaceOne({ _id: avisoId }, {
  _id: avisoId, destinatarioPerfil: "PACIENTE", destinatarioId: cancelamento.pacienteId,
  tipo: "CANCELAMENTO_CONSULTA", canal: "SISTEMA", simulada: true,
  consultaId: cancelamento.consultaId, filaEsperaId: null, statusConsulta: "CANCELADA",
  antecedenciaHoras: null, titulo: "Consulta cancelada", mensagem: "Seu cancelamento foi registrado.",
  dadosConsulta: { dataHora: cancelamento.dataHoraConsulta, medicoNome: medico.nome },
  status: "ENVIADA", agendadaPara: cancelamento.ocorridoEm, enviadaEm: cancelamento.ocorridoEm,
  tentativas: NumberInt(1), erro: null, criadoEm: cancelamento.ocorridoEm
}, { upsert: true });

print("Demo da fila: cancele a consulta " + vaga._id.toHexString() + " de " + vaga.inicio.toISOString());
