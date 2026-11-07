// =============================================================================
// ConsultaFácil — Esquema (validador $jsonSchema) e índices da coleção `especialidades`
// Referência: medicos-agenda.md
//
// Idempotente: cria a coleção se não existir ou atualiza o validador (collMod).
// Requer MongoDB 6 ou superior (índices parciais com $in).
//
// Executar:
//   docker exec -i consultafacil-mongo mongosh consultafacil      --quiet < database/init/05-esquema-especialidades.js
//   docker exec -i consultafacil-mongo mongosh consultafacil_test --quiet < database/init/05-esquema-especialidades.js
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

aplicarEsquema("especialidades", {
  $jsonSchema: {
    bsonType: "object",
    required: ["codigo", "nome", "ativa"],
    additionalProperties: false,
    properties: {
      _id: OID,
      codigo: {
        bsonType: "string",
        pattern: "^[a-z0-9-]+$",
        description: "Identificador estável, minúsculo, com hífens. Único"
      },
      nome: { bsonType: "string", minLength: 1, maxLength: 100 },
      ativa: { bsonType: "bool" }
    }
  }
});

db.especialidades.createIndex({ codigo: 1 }, { name: "uk_especialidade_codigo", unique: true });

print("OK especialidades -> índices: " + db.especialidades.getIndexes().map((i) => i.name).join(", "));
