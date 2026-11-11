const ATIVOS = ["AGENDADA", "CONFIRMADA"];

const validator = {
  $jsonSchema: {
    bsonType: "object",
    required: ["pacienteId", "medicoId", "especialidadeId", "inicio", "fim", "status", "criadaEm"],
    properties: {
      pacienteId:         { bsonType: "objectId" },
      medicoId:           { bsonType: "objectId" },
      especialidadeId:    { bsonType: "objectId" },
      inicio:             { bsonType: "date" },
      fim:                 { bsonType: "date" },
      status:             { enum: ["AGENDADA", "CONFIRMADA", "CANCELADA", "CONCLUIDA", "FALTA"] },
      // Opcionais para manter compatibilidade com consultas anteriores à auditoria.
      versao: { bsonType: "int", minimum: 0 },
      eventosPendentes: {
        bsonType: "array", maxItems: 50,
        items: {
          bsonType: "object",
          required: ["_id", "consultaId", "sequencia", "evento", "statusAnterior", "statusNovo",
            "pacienteId", "medicoId", "especialidadeId", "dataHoraConsulta", "fimConsulta", "ator", "ocorridoEm"],
          properties: {
            _id: { bsonType: "objectId" }, consultaId: { bsonType: "objectId" },
            sequencia: { bsonType: "int", minimum: 1 },
            evento: { enum: ["CRIACAO", "CONFIRMACAO", "ALTERACAO", "CANCELAMENTO", "CONCLUSAO", "FALTA"] },
            statusAnterior: { enum: [null, "AGENDADA", "CONFIRMADA", "CANCELADA", "CONCLUIDA", "FALTA"] },
            statusNovo: { enum: ["AGENDADA", "CONFIRMADA", "CANCELADA", "CONCLUIDA", "FALTA"] },
            pacienteId: { bsonType: "objectId" }, medicoId: { bsonType: "objectId" },
            especialidadeId: { bsonType: "objectId" },
            dataHoraConsulta: { bsonType: "date" }, fimConsulta: { bsonType: "date" },
            ocorridoEm: { bsonType: "date" },
            ator: { bsonType: "object", required: ["perfil", "id"], properties: {
              perfil: { enum: ["PACIENTE", "MEDICO", "SECRETARIA_ADMIN"] }, id: { bsonType: "objectId" }
            }}
          }
        }
      },
      criadaEm:           { bsonType: "date" },
      atualizadaEm:       { bsonType: "date" },
      motivoCancelamento: { bsonType: "string" }
    }
  },
  $expr: { $lt: ["$inicio", "$fim"] }
};

if (db.getCollectionNames().includes("consultas")) {
  db.runCommand({ collMod: "consultas", validator, validationLevel: "strict" });
} else {
  db.createCollection("consultas", { validator });
}

// remove o índice antigo (data + horario + ativa), substituído pelos de baixo
if (db.consultas.getIndexes().some(i => i.name === "uk_consulta_ativa")) {
  db.consultas.dropIndex("uk_consulta_ativa");
}

const somenteAtivas = { status: { $in: ATIVOS } };
db.consultas.createIndex({ medicoId: 1, inicio: 1 },
  { unique: true, name: "uk_consulta_medico_inicio", partialFilterExpression: somenteAtivas });
db.consultas.createIndex({ pacienteId: 1, inicio: 1 },
  { unique: true, name: "uk_consulta_paciente_inicio", partialFilterExpression: somenteAtivas });
db.consultas.createIndex({ medicoId: 1, inicio: 1, fim: 1 }, { name: "ix_consulta_medico_periodo" });
db.consultas.createIndex({ pacienteId: 1, inicio: -1 }, { name: "ix_consulta_paciente" });