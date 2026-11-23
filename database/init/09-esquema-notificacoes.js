// =============================================================================
// ConsultaFácil — Esquema (validador $jsonSchema) e índices da coleção `notificacoes`
// Referência: notificacoes.md
//
// Idempotente: cria a coleção se não existir ou atualiza o validador (collMod).
// Requer MongoDB 6 ou superior (índices parciais com $in).
//
// Executar:
//   docker exec -i consultafacil-mongo mongosh consultafacil      --quiet < database/init/08-esquema-notificacoes.js
//   docker exec -i consultafacil-mongo mongosh consultafacil_test --quiet < database/init/08-esquema-notificacoes.js
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

aplicarEsquema("notificacoes", {
  $jsonSchema: {
    bsonType: "object",
    required: [
      "destinatarioPerfil", "destinatarioId", "tipo", "canal", "simulada", "consultaId",
      "filaEsperaId", "statusConsulta", "antecedenciaHoras", "titulo", "mensagem",
      "dadosConsulta", "status", "agendadaPara", "enviadaEm", "tentativas", "erro", "criadoEm"
    ],
    additionalProperties: false,
    properties: {
      _id: OID,
      destinatarioPerfil: { enum: ["PACIENTE", "MEDICO"] },
      destinatarioId: OID, // pacientes._id ou medicos._id, conforme destinatarioPerfil
      tipo: {
        enum: ["CONFIRMACAO_AGENDAMENTO", "LEMBRETE_CONSULTA", "CANCELAMENTO_CONSULTA", "VAGA_LIBERADA"]
      },
      canal: {
        enum: ["SISTEMA", "EMAIL_TESTE"], // SMS e WHATSAPP reservados para evolução futura
        description: "No MVP apenas SISTEMA e EMAIL_TESTE"
      },
      simulada: { bsonType: "bool", description: "true em todas as notificações do MVP" },
      consultaId: OID,
      filaEsperaId: OID_OU_NULO,
      statusConsulta: {
        enum: ["AGENDADA", "CONFIRMADA", "CANCELADA", "CONCLUIDA", "FALTA"],
        description: "Status da consulta no momento da criação da notificação (cópia)"
      },
      antecedenciaHoras: { bsonType: ["int", "null"], minimum: 1 },
      titulo: { bsonType: "string", minLength: 1, maxLength: 120 },
      mensagem: {
        bsonType: "string",
        minLength: 1,
        maxLength: 500,
        description: "Não deve conter CPF, motivo, diagnóstico ou outro dado de saúde"
      },
      dadosConsulta: {
        bsonType: "object",
        required: ["dataHora", "medicoNome"],
        additionalProperties: false,
        properties: {
          dataHora: DATA,
          medicoNome: { bsonType: "string", minLength: 1 }
        }
      },
      status: { enum: ["AGENDADA", "ENVIADA", "FALHA", "CANCELADA"] },
      agendadaPara: DATA,
      enviadaEm: DATA_OU_NULA,
      tentativas: { bsonType: "int", minimum: 0, maximum: 3 },
      erro: { bsonType: ["string", "null"], maxLength: 500 },
      lidaEm: DATA_OU_NULA,
      confirmadaEm: DATA_OU_NULA,
      criadoEm: DATA
    },
    allOf: [
      {
        // Regras por tipo: destinatário, status da consulta aceito, campos condicionais
        anyOf: [
          {
            properties: {
              tipo: { enum: ["CONFIRMACAO_AGENDAMENTO"] },
              destinatarioPerfil: { enum: ["PACIENTE"] },
              statusConsulta: { enum: ["AGENDADA"] },
              antecedenciaHoras: NULO,
              filaEsperaId: NULO,
              confirmadaEm: NULO
            }
          },
          {
            properties: {
              tipo: { enum: ["LEMBRETE_CONSULTA"] },
              destinatarioPerfil: { enum: ["PACIENTE"] },
              statusConsulta: { enum: ["AGENDADA", "CONFIRMADA"] },
              antecedenciaHoras: { bsonType: "int" },
              filaEsperaId: NULO
            }
          },
          {
            properties: {
              tipo: { enum: ["CANCELAMENTO_CONSULTA"] },
              statusConsulta: { enum: ["CANCELADA"] },
              antecedenciaHoras: NULO,
              filaEsperaId: NULO,
              confirmadaEm: NULO
            }
          },
          {
            properties: {
              tipo: { enum: ["VAGA_LIBERADA"] },
              destinatarioPerfil: { enum: ["PACIENTE"] },
              statusConsulta: { enum: ["CANCELADA"] },
              antecedenciaHoras: NULO,
              filaEsperaId: { bsonType: "objectId" },
              confirmadaEm: NULO
            }
          }
        ]
      },
      {
        // Regras por status de entrega
        anyOf: [
          {
            properties: {
              status: { enum: ["AGENDADA", "CANCELADA"] },
              enviadaEm: NULO,
              erro: NULO
            }
          },
          {
            properties: {
              status: { enum: ["ENVIADA"] },
              enviadaEm: { bsonType: "date" },
              erro: NULO
            }
          },
          {
            properties: {
              status: { enum: ["FALHA"] },
              enviadaEm: NULO,
              erro: { bsonType: "string", minLength: 1 }
            }
          }
        ]
      }
    ]
  }
});

db.notificacoes.createIndex(
  { destinatarioPerfil: 1, destinatarioId: 1, criadoEm: -1 },
  { name: "idx_notif_destinatario" }
);
db.notificacoes.createIndex(
  { agendadaPara: 1 },
  { name: "idx_notif_envio_pendente", partialFilterExpression: { status: "AGENDADA" } }
);
db.notificacoes.createIndex(
  { consultaId: 1, tipo: 1, status: 1 },
  { name: "idx_notif_consulta" }
);
db.notificacoes.createIndex(
  { consultaId: 1, tipo: 1, destinatarioId: 1, canal: 1, antecedenciaHoras: 1 },
  { name: "uq_notif_idempotencia", unique: true }
);
db.notificacoes.createIndex(
  { agendadaPara: 1 },
  { name: "idx_notif_lembretes_periodo", partialFilterExpression: { tipo: "LEMBRETE_CONSULTA" } }
);

print("OK notificacoes -> índices: " + db.notificacoes.getIndexes().map((i) => i.name).join(", "));
