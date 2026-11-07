// =============================================================================
// ConsultaFácil — Esquema (validador $jsonSchema) e índices da coleção `medicos`
// Referência: medicos-agenda.md (e usuarios-pacientes.md para usuarioId)
//
// Idempotente: cria a coleção se não existir ou atualiza o validador (collMod).
// Requer MongoDB 6 ou superior (índices parciais com $in).
//
// Executar:
//   docker exec -i consultafacil-mongo mongosh consultafacil      --quiet < database/init/06-esquema-medicos.js
//   docker exec -i consultafacil-mongo mongosh consultafacil_test --quiet < database/init/06-esquema-medicos.js
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

aplicarEsquema("medicos", {
  $jsonSchema: {
    bsonType: "object",
    required: ["nome", "crmNumero", "crmUf", "especialidadeIds", "ativo"],
    additionalProperties: false,
    properties: {
      _id: OID,
      // Vínculo opcional com o login (usuarios.perfil = MEDICO), citado em usuarios-pacientes.md
      usuarioId: OID,
      nome: { bsonType: "string", minLength: 3, maxLength: 120 },
      crmNumero: {
        bsonType: "string",
        pattern: "^[0-9]{1,10}$",
        description: "Número do CRM como texto, preservando zeros à esquerda"
      },
      crmUf: {
        bsonType: "string",
        pattern: "^[A-Z]{2}$",
        description: "UF do CRM, duas letras maiúsculas"
      },
      especialidadeIds: {
        bsonType: "array",
        minItems: 1,
        uniqueItems: true,
        items: OID,
        description: "Referências a especialidades._id. Mínimo 1, sem repetição"
      },
      ativo: { bsonType: "bool" }
    }
  }
});

db.medicos.createIndex({ crmUf: 1, crmNumero: 1 }, { name: "uk_medico_crm", unique: true });
db.medicos.createIndex({ especialidadeIds: 1, ativo: 1 }, { name: "ix_medico_especialidade" });
db.medicos.createIndex(
  { usuarioId: 1 },
  {
    name: "uk_medico_usuario",
    unique: true,
    partialFilterExpression: { usuarioId: { $exists: true } }
  }
);

print("OK medicos -> índices: " + db.medicos.getIndexes().map((i) => i.name).join(", "));
