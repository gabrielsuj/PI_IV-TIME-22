// =============================================================================
// ConsultaFácil — Esquema (validador $jsonSchema) e índices da coleção `filaEspera`
// Referência: filaEspera.md
//
// Idempotente: cria a coleção se não existir ou atualiza o validador (collMod).
// Requer MongoDB 6 ou superior (índices parciais com $in).
//
// Executar:
//   docker exec -i consultafacil-mongo mongosh consultafacil      --quiet < database/init/07-esquema-fila-espera.js
//   docker exec -i consultafacil-mongo mongosh consultafacil_test --quiet < database/init/07-esquema-fila-espera.js
//
// Ao inserir pelo mongosh, use NumberInt(...) nos campos "int". Violação do
// validador retorna o código 121 (API: 400); violação de índice único, 11000 (API: 409).
// =============================================================================

const OPCOES_VALIDACAO = { validationLevel: "strict", validationAction: "error" };

function aplicarEsquema(nome, validator) {
  const existe = db.getCollectionInfos({ name: nome }).length > 0;
  if (existe) {
    db.runCommand({ collMod: nome, validator: validator, ...OPCOES_VALIDACAO });
  } else {
    db.createCollection(nome, { validator: validator, ...OPCOES_VALIDACAO });
  }
  print("OK esquema aplicado: " + nome);
}

const OID = { bsonType: "objectId" };
const OID_OU_NULO = { bsonType: ["objectId", "null"] };
const DATA = { bsonType: "date" };
const DATA_OU_NULA = { bsonType: ["date", "null"] };
const NULO = { bsonType: "null" };

const ultimaSinalizacaoObj = {
  bsonType: "object",
  required: ["consultaOrigemId", "medicoId", "dataHora", "sinalizadaEm", "notificacaoId"],
  additionalProperties: false,
  properties: {
    consultaOrigemId: OID, // consulta CANCELADA que liberou a vaga
    medicoId: OID,
    dataHora: DATA, // início do horário liberado
    sinalizadaEm: DATA,
    notificacaoId: OID // notificacoes._id (tipo VAGA_LIBERADA)
  }
};

aplicarEsquema("filaEspera", {
  $and: [
    {
      $jsonSchema: {
        bsonType: "object",
        required: [
          "pacienteId", "especialidadeId", "medicoId", "turnoPreferido", "status",
          "ultimaSinalizacao", "consultaAgendadaId", "atendidaEm", "validoAte",
          "registradoPor", "criadoEm", "atualizadoEm"
        ],
        additionalProperties: false,
        properties: {
          _id: OID,
          pacienteId: OID,
          especialidadeId: OID,
          medicoId: {
            bsonType: ["objectId", "null"],
            description: "null = qualquer médico da especialidade. Gravar null explicitamente"
          },
          turnoPreferido: {
            enum: ["MANHA", "TARDE", "QUALQUER"],
            description: "Padrão na criação (API): QUALQUER"
          },
          dataInicio: DATA, // opcional: ausente = a partir do registro
          dataFim: DATA, // opcional: ausente = até validoAte
          status: {
            enum: ["AGUARDANDO", "SINALIZADA", "ATENDIDA", "CANCELADA", "EXPIRADA"],
            description: "Padrão na criação (API): AGUARDANDO"
          },
          ultimaSinalizacao: { anyOf: [NULO, ultimaSinalizacaoObj] },
          consultaAgendadaId: OID_OU_NULO,
          atendidaEm: DATA_OU_NULA,
          validoAte: DATA,
          registradoPor: {
            bsonType: "object",
            required: ["perfil", "id"],
            additionalProperties: false,
            properties: {
              perfil: { enum: ["PACIENTE", "SECRETARIA", "ADMINISTRADOR"] },
              id: OID
            }
          },
          criadoEm: DATA,
          atualizadoEm: DATA
        },
        // Campos condicionais por status
        anyOf: [
          {
            // AGUARDANDO, CANCELADA, EXPIRADA: sem sinalização, sem consulta agendada
            properties: {
              status: { enum: ["AGUARDANDO", "CANCELADA", "EXPIRADA"] },
              ultimaSinalizacao: NULO,
              consultaAgendadaId: NULO,
              atendidaEm: NULO
            }
          },
          {
            // SINALIZADA: ultimaSinalizacao obrigatória
            properties: {
              status: { enum: ["SINALIZADA"] },
              ultimaSinalizacao: { bsonType: "object" },
              consultaAgendadaId: NULO,
              atendidaEm: NULO
            }
          },
          {
            // ATENDIDA: consultaAgendadaId e atendidaEm obrigatórios
            properties: {
              status: { enum: ["ATENDIDA"] },
              ultimaSinalizacao: NULO,
              consultaAgendadaId: { bsonType: "objectId" },
              atendidaEm: { bsonType: "date" }
            }
          }
        ]
      }
    },
    {
      // dataFim >= dataInicio (quando ambos informados)
      $expr: {
        $or: [
          { $ne: [{ $type: "$dataInicio" }, "date"] },
          { $ne: [{ $type: "$dataFim" }, "date"] },
          { $gte: ["$dataFim", "$dataInicio"] }
        ]
      }
    }
  ]
});

const STATUS_FILA_ATIVOS = { status: { $in: ["AGUARDANDO", "SINALIZADA"] } };

db.filaEspera.createIndex({ pacienteId: 1, status: 1 }, { name: "idx_fila_paciente_status" });
db.filaEspera.createIndex(
  { status: 1, especialidadeId: 1, medicoId: 1, criadoEm: 1 },
  { name: "idx_fila_compatibilidade" }
);
db.filaEspera.createIndex(
  { pacienteId: 1, especialidadeId: 1, medicoId: 1 },
  { name: "uq_fila_interesse_ativo", unique: true, partialFilterExpression: STATUS_FILA_ATIVOS }
);
db.filaEspera.createIndex(
  { validoAte: 1 },
  { name: "idx_fila_validade", partialFilterExpression: STATUS_FILA_ATIVOS }
);
db.filaEspera.createIndex(
  { atendidaEm: 1 },
  { name: "idx_fila_atendidas", partialFilterExpression: { status: "ATENDIDA" } }
);

print("OK filaEspera -> índices: " + db.filaEspera.getIndexes().map((i) => i.name).join(", "));
