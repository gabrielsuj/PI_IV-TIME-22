// Executado automaticamente somente na inicialização de um volume Mongo vazio.
// Para uma base existente, executar este arquivo no mongosh da base consultafacil.
// Não remove ou corrige dados existentes; duplicidades devem ser resolvidas antes.
// Usa o banco selecionado pelo mongosh (desenvolvimento, teste ou demonstração).
const pacienteValidator = { $jsonSchema: {
  bsonType: "object",
  required: ["nome", "cpf", "email"],
  properties: {
    nome: { bsonType: "string", pattern: "\\S" },
    cpf: { bsonType: "string", pattern: "^[0-9]{11}$" },
    email: { bsonType: "string", maxLength: 254, pattern: "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$" }
  }
}};
if (db.getCollectionNames().includes("pacientes")) {
  const resultado = db.runCommand({ collMod: "pacientes", validator: pacienteValidator,
    validationLevel: "strict", validationAction: "error" });
  if (!resultado.ok) throw new Error("Não foi possível configurar a validação de pacientes.");
} else {
  db.createCollection("pacientes", { validator: pacienteValidator, validationAction: "error" });
}
// A API armazena CPF sem pontuação e e-mail em letras minúsculas.
db.pacientes.createIndex({ cpf: 1 }, { unique: true, name: "uk_paciente_cpf" });
db.pacientes.createIndex({ email: 1 }, { unique: true, name: "uk_paciente_email" });
