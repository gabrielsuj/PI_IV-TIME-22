// Logins da equipe e de pacientes da carga ampliada. Todos com a senha: Demo@123
// Executado depois de 03 e 04 (médicos e pacientes já existem).
//
// Mesmo formato de senha de 02-usuarios.js (PBKDF2WithHmacSHA256, compatível com SenhaHasher).
const crypto = require("crypto");

const SENHA_DEMO = "Demo@123";
const ITERACOES = 210000;

function senhaHash(senha) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.pbkdf2Sync(senha, salt, ITERACOES, 32, "sha256");
  return { hash: hash.toString("base64"), salt: salt.toString("base64"),
    algoritmo: "PBKDF2WithHmacSHA256", iteracoes: NumberInt(ITERACOES) };
}

// CPF fictício válido: 9 dígitos de base + 2 dígitos verificadores calculados.
function cpf(base) {
  const d = base.split("").map(Number);
  for (const tamanho of [9, 10]) {
    const soma = d.slice(0, tamanho).reduce((s, n, i) => s + n * (tamanho + 1 - i), 0);
    const resto = (soma * 10) % 11;
    d.push(resto === 10 ? 0 : resto);
  }
  return d.join("");
}

const usr = (n) => ObjectId("6000000000000000000000" + n.toString(16).padStart(2, "0"));
const med = (n) => ObjectId("1000000000000000000000" + n.toString(16).padStart(2, "0"));

// [id do usuário, nome, e-mail, CPF, perfil, médico vinculado]
const equipe = [
  [4, "Bruno Lima", "bruno.lima", cpf("284716530"), "MEDICO", 2],
  [5, "Carolina Mendes", "carolina.mendes", cpf("317058249"), "MEDICO", 3],
  [6, "Rafael Duarte", "rafael.duarte", cpf("406193872"), "MEDICO", 4],
  [7, "Juliana Ferraz", "juliana.ferraz", cpf("158920467"), "MEDICO", 5],
  [8, "Marcelo Tavares", "marcelo.tavares", cpf("263847105"), "MEDICO", 6],
  [9, "Patrícia Nogueira", "patricia.nogueira", cpf("590274618"), "MEDICO", 7],
  [10, "Eduardo Prado", "eduardo.prado", cpf("731508426"), "MEDICO", 8],
  [11, "Fernanda Lopes", "fernanda.lopes", cpf("825361904"), "MEDICO", 9],
  [12, "Gustavo Ribeiro", "gustavo.ribeiro", cpf("648027153"), "MEDICO", 10],
  [13, "Lucas Andrade", "lucas.andrade", cpf("372914580"), "SECRETARIA_ADMIN", null],
  // Pacientes de 03-pacientes.js que também têm login (mesmo CPF e e-mail do cadastro)
  [14, "Carla Souza — Fictícia", "carla.souza", "12345678909", "PACIENTE", null],
  [15, "Diego Santos — Fictício", "diego.santos", "98765432100", "PACIENTE", null]
];

const agora = new Date();
equipe.forEach(([n, nome, login, numeroCpf, perfil]) =>
  // Só cria se ainda não existir: repetir a carga não troca senha nem salt.
  db.usuarios.updateOne(
    { _id: usr(n) },
    { $setOnInsert: { nome, cpf: numeroCpf, email: login + "@consultafacil.demo", perfil,
      senha: senhaHash(SENHA_DEMO), ativo: true, criadoEm: agora, atualizadoEm: agora } },
    { upsert: true }));

// Vincula cada login de médico ao cadastro correspondente (usado para "só o próprio médico").
equipe.filter((u) => u[5] !== null).forEach(([n, , , , , medico]) =>
  db.medicos.updateOne({ _id: med(medico) }, { $set: { usuarioId: usr(n) } }));

print("OK carga 05: " + db.usuarios.countDocuments() + " usuários, " +
  db.medicos.countDocuments({ usuarioId: { $exists: true } }) + " médicos com login");
