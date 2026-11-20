// Exemplo entregue à central da #35. Com a API no ar, o agendador da #34 gera os demais lembretes;
// ele não cria outro para esta consulta porque ela já tem um (NotificacaoRepository.temLembrete).
const pacienteId = ObjectId("400000000000000000000001");
const consulta = db.consultas.findOne({ pacienteId, status: "AGENDADA", inicio: { $gt: new Date() } },
  {}, { sort: { inicio: 1 } });
if (consulta) {
  const medico = db.medicos.findOne({ _id: consulta.medicoId });
  // Janela da simulação calculada a partir da reserva: é estável ao repetir o seed.
  const horas = Math.max(1, Math.ceil((consulta.inicio - consulta.criadaEm) / 3600000));
  const chave = { consultaId: consulta._id, tipo: "LEMBRETE_CONSULTA", destinatarioId: pacienteId,
    canal: "SISTEMA", antecedenciaHoras: NumberInt(horas) };
  db.notificacoes.updateOne(chave, { $setOnInsert: {
    destinatarioPerfil: "PACIENTE", simulada: true, filaEsperaId: null, statusConsulta: "AGENDADA",
    titulo: "Lembrete de consulta", mensagem: "Confira o horário e confirme sua presença.",
    dadosConsulta: { dataHora: consulta.inicio, medicoNome: medico.nome },
    status: "ENVIADA", agendadaPara: consulta.criadaEm, enviadaEm: consulta.criadaEm,
    tentativas: NumberInt(1), erro: null, criadoEm: consulta.criadaEm
  } }, { upsert: true });
  print("Lembrete simulado disponível para Paulo na central de notificações.");
}
