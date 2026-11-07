// =============================================================================
// ConsultaFácil — Esquema (validador $jsonSchema) e índices da coleção `historico`
// Referência: historico.md
//
// Idempotente: cria a coleção se não existir ou atualiza o validador (collMod).
// Requer MongoDB 6 ou superior (índices parciais com $in).
//
// Executar:
//   docker exec -i consultafacil-mongo mongosh consultafacil      --quiet < database/init/09-esquema-historico.js
//   docker exec -i consultafacil-mongo mongosh consultafacil_test --quiet < database/init/09-esquema-historico.js
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
const NULO = { bsonType: "null" };
// Domínio único de status da consulta (definição de referência: historico.md)
const STATUS_CONSULTA = ["AGENDADA", "CONFIRMADA", "CANCELADA", "CONCLUIDA", "FALTA"];

const ATORES = ["PACIENTE", "MEDICO", "SECRETARIA", "ADMINISTRADOR", "SISTEMA"];
const ATIVAS = ["AGENDADA", "CONFIRMADA"];

// Monta o ramo de validação de um evento (tabela "Eventos" de historico.md)
function ramoHistorico(def) {
  const sistema = def.atores.length === 1 && def.atores[0] === "SISTEMA";
  const ramo = {
    properties: {
      evento: { enum: [def.evento] },
      statusAnterior: def.anterior === null ? NULO : { enum: def.anterior },
      statusNovo: { enum: def.novo },
      ator: {
        bsonType: "object",
        properties: {
          perfil: { enum: def.atores },
          id: sistema ? NULO : OID // id = null somente quando SISTEMA
        }
      }
    }
  };
  if (def.exige && def.exige.length > 0) {
    ramo.required = def.exige;
  }
  if (def.proibe && def.proibe.length > 0) {
    ramo.not = { anyOf: def.proibe.map((campo) => ({ required: [campo] })) };
  }
  return ramo;
}

aplicarEsquema("historico", {
  $and: [
    {
      $jsonSchema: {
        bsonType: "object",
        required: [
          "consultaId", "sequencia", "evento", "statusAnterior", "statusNovo",
          "pacienteId", "medicoId", "dataHoraConsulta", "ator", "ocorridoEm"
        ],
        additionalProperties: false,
        properties: {
          _id: OID,
          consultaId: OID,
          sequencia: { bsonType: "int", minimum: 1 },
          evento: {
            enum: [
              "CRIACAO", "CONFIRMACAO", "ALTERACAO", "CANCELAMENTO",
              "CONCLUSAO", "FALTA", "SINALIZACAO_FILA"
            ]
          },
          statusAnterior: {
            anyOf: [NULO, { enum: STATUS_CONSULTA }],
            description: "null apenas em CRIACAO"
          },
          statusNovo: { enum: STATUS_CONSULTA },
          pacienteId: OID,
          medicoId: OID, // médico da consulta após o evento
          dataHoraConsulta: DATA, // início do horário após o evento
          ator: {
            bsonType: "object",
            required: ["perfil", "id"],
            additionalProperties: false,
            properties: {
              perfil: { enum: ATORES },
              id: OID_OU_NULO // null quando SISTEMA
            }
          },
          alteracoes: {
            bsonType: "array",
            minItems: 1,
            items: {
              bsonType: "object",
              required: ["campo", "valorAnterior", "valorNovo"],
              additionalProperties: false,
              properties: {
                campo: { bsonType: "string", minLength: 1 },
                valorAnterior: { bsonType: ["string", "number", "date", "objectId"] },
                valorNovo: { bsonType: ["string", "number", "date", "objectId"] }
              }
            }
          },
          motivo: {
            bsonType: ["string", "null"],
            maxLength: 255,
            description: "Não deve conter dados clínicos"
          },
          totalSinalizados: { bsonType: "int", minimum: 0 },
          ocorridoEm: DATA
        },
        // Regras por evento: transição de status, atores permitidos e campos específicos
        anyOf: [
          ramoHistorico({
            evento: "CRIACAO", anterior: null, novo: ["AGENDADA"],
            atores: ["PACIENTE", "SECRETARIA", "ADMINISTRADOR"],
            proibe: ["alteracoes", "totalSinalizados"]
          }),
          ramoHistorico({
            evento: "CONFIRMACAO", anterior: ["AGENDADA"], novo: ["CONFIRMADA"],
            atores: ["PACIENTE", "MEDICO", "SECRETARIA", "ADMINISTRADOR"],
            proibe: ["alteracoes", "totalSinalizados"]
          }),
          ramoHistorico({
            evento: "ALTERACAO", anterior: ATIVAS, novo: ATIVAS, // igualdade validada no $expr abaixo
            atores: ["SECRETARIA", "ADMINISTRADOR"],
            exige: ["alteracoes"], proibe: ["totalSinalizados"]
          }),
          ramoHistorico({
            evento: "CANCELAMENTO", anterior: ATIVAS, novo: ["CANCELADA"],
            atores: ["PACIENTE", "MEDICO", "SECRETARIA", "ADMINISTRADOR"],
            proibe: ["alteracoes", "totalSinalizados"]
          }),
          ramoHistorico({
            evento: "CONCLUSAO", anterior: ATIVAS, novo: ["CONCLUIDA"],
            atores: ["MEDICO", "SECRETARIA", "ADMINISTRADOR"],
            proibe: ["alteracoes", "totalSinalizados"]
          }),
          ramoHistorico({
            evento: "FALTA", anterior: ATIVAS, novo: ["FALTA"],
            atores: ["MEDICO", "SECRETARIA", "ADMINISTRADOR"],
            proibe: ["alteracoes", "totalSinalizados"]
          }),
          ramoHistorico({
            evento: "SINALIZACAO_FILA", anterior: ["CANCELADA"], novo: ["CANCELADA"],
            atores: ["SISTEMA"],
            exige: ["totalSinalizados"], proibe: ["alteracoes"]
          })
        ]
      }
    },
    {
      // Em ALTERACAO e SINALIZACAO_FILA, statusNovo é igual a statusAnterior
      $expr: {
        $or: [
          { $not: [{ $in: ["$evento", ["ALTERACAO", "SINALIZACAO_FILA"]] }] },
          { $eq: ["$statusAnterior", "$statusNovo"] }
        ]
      }
    }
  ]
});

db.historico.createIndex({ consultaId: 1, sequencia: 1 }, { name: "uq_hist_consulta_sequencia", unique: true });
db.historico.createIndex({ pacienteId: 1, ocorridoEm: -1 }, { name: "idx_hist_paciente_data" });
db.historico.createIndex({ medicoId: 1, ocorridoEm: -1 }, { name: "idx_hist_medico_data" });
db.historico.createIndex({ evento: 1, ocorridoEm: -1 }, { name: "idx_hist_evento_data" });
db.historico.createIndex({ ocorridoEm: -1 }, { name: "idx_hist_data" });

print("OK historico -> índices: " + db.historico.getIndexes().map((i) => i.name).join(", "));
