/*
Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

// Validadores e índices de agendas e bloqueios
function aplicarValidador(nome, validator) {
  if (db.getCollectionNames().includes(nome)) {
    db.runCommand({ collMod: nome, validator, validationLevel: "strict" });
  } else {
    db.createCollection(nome, { validator });
  }
}

aplicarValidador("agendas", {
  $jsonSchema: {
    bsonType: "object", required: ["medicoId", "fusoHorario", "duracaoMinutos", "jornadas"],
    properties: {
      medicoId:       { bsonType: "objectId" },
      fusoHorario:    { bsonType: "string" },
      duracaoMinutos: { bsonType: "int", minimum: 5, maximum: 240 },
      jornadas: { bsonType: "array", items: {
        bsonType: "object", required: ["diaSemana", "faixas"],
        properties: {
          diaSemana: { bsonType: "int", minimum: 1, maximum: 7 },  
          faixas: { bsonType: "array", minItems: 1, items: {
            bsonType: "object", required: ["inicioMinuto", "fimMinuto"],
            properties: {
              inicioMinuto: { bsonType: "int", minimum: 0, maximum: 1439 },
              fimMinuto:    { bsonType: "int", minimum: 1, maximum: 1440 }
            }
          }}
        }
      }}
    }
  },
  // toda faixa precisa ter início < fim
  $expr: { $allElementsTrue: [ { $map: {
    input: { $reduce: { input: "$jornadas.faixas", initialValue: [],
                        in: { $concatArrays: ["$$value", "$$this"] } } },
    as: "f", in: { $lt: ["$$f.inicioMinuto", "$$f.fimMinuto"] }
  }}]}
});

aplicarValidador("bloqueios", {
  $jsonSchema: {
    bsonType: "object", required: ["medicoId", "inicio", "fim"],
    properties: {
      medicoId: { bsonType: "objectId" },
      inicio:   { bsonType: "date" },
      fim:      { bsonType: "date" },
      motivo:   { bsonType: "string" }
    }
  },
  $expr: { $lt: ["$inicio", "$fim"] }   // regra: início anterior ao fim
});

// Índices
db.agendas.createIndex({ medicoId: 1 }, { unique: true, name: "uk_agenda_medico" });   // 1 agenda por médico
db.bloqueios.createIndex({ medicoId: 1, inicio: 1, fim: 1 }, { name: "ix_bloqueio_medico_periodo" });