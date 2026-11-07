// =============================================================================
// ConsultaFácil — Esquema (validador $jsonSchema) e índices da coleção `usuarios`
// Referência: usuarios-pacientes.md
//
// Idempotente: cria a coleção se não existir ou atualiza o validador (collMod).
// Requer MongoDB 6 ou superior (índices parciais com $in).
//
// Executar:
//   docker exec -i consultafacil-mongo mongosh consultafacil      --quiet < database/init/04-esquema-usuarios.js
//   docker exec -i consultafacil-mongo mongosh consultafacil_test --quiet < database/init/04-esquema-usuarios.js
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
const DATA = { bsonType: "date" };
// E-mail em minúsculas (mesmo padrão usado em usuarios e pacientes)
const EMAIL_REGEX = "^[^\\s@A-Z]+@[^\\s@A-Z]+\\.[^\\s@A-Z]+$";

aplicarEsquema("usuarios", {
  $jsonSchema: {
    bsonType: "object",
    required: ["nome", "cpf", "email", "senha", "perfil", "ativo", "criadoEm", "atualizadoEm"],
    additionalProperties: false,
    properties: {
      _id: OID,
      nome: {
        bsonType: "string",
        minLength: 3,
        maxLength: 120,
        pattern: "^\\S.*\\S$", // sem espaços nas extremidades
        description: "Nome de exibição, 3 a 120 caracteres, sem espaços nas extremidades"
      },
      cpf: {
        bsonType: "string",
        pattern: "^[0-9]{11}$",
        description: "CPF somente com os 11 dígitos, sem máscara. Único. Dígitos verificadores validados na API"
      },
      email: {
        bsonType: "string",
        pattern: EMAIL_REGEX,
        description: "Login. Sempre em minúsculas, único"
      },
      senha: {
        bsonType: "object",
        required: ["hash", "salt", "algoritmo", "iteracoes"],
        additionalProperties: false,
        properties: {
          hash: { bsonType: "string" },
          salt: { bsonType: "string" },
          algoritmo: { bsonType: "string" },
          iteracoes: { bsonType: "int", minimum: 1 }
        }
      },
      perfil: { enum: ["PACIENTE", "MEDICO", "SECRETARIA_ADMIN"] },
      ativo: { bsonType: "bool" },
      ultimoAcessoEm: DATA, // ausente enquanto nunca acessou (não usar null)
      criadoEm: DATA,
      atualizadoEm: DATA
    }
  }
});

db.usuarios.createIndex({ email: 1 }, { unique: true });
db.usuarios.createIndex({ cpf: 1 }, { unique: true });
db.usuarios.createIndex({ perfil: 1, ativo: 1 });

print("OK usuarios -> índices: " + db.usuarios.getIndexes().map((i) => i.name).join(", "));
