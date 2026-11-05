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