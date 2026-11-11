// Usuários de demonstração (#9). Todos com a senha: Demo@123
// Executado depois de 01-agenda.js (os médicos já existem).
//
// O hash é gerado aqui com o mesmo algoritmo da API (PBKDF2WithHmacSHA256, 32 bytes),
// então o login funciona sem precisar cadastrar ninguém pela API.
const crypto = require("crypto");

const SENHA_DEMO = "Demo@123";
const ITERACOES = 210000;

function senhaHash(senha) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.pbkdf2Sync(senha, salt, ITERACOES, 32, "sha256");
  return {
    hash: hash.toString("base64"),
    salt: salt.toString("base64"),
    algoritmo: "PBKDF2WithHmacSHA256",
    iteracoes: NumberInt(ITERACOES)   // o validador exige int, e o mongosh usa double por padrão
  };
}

const agora = new Date();
const usuarios = [
  { _id: ObjectId("600000000000000000000001"), nome: "Sofia Secretaria — Fictícia", cpf: "52998224725",
    email: "secretaria@consultafacil.demo", perfil: "SECRETARIA_ADMIN" },
  { _id: ObjectId("600000000000000000000002"), nome: "Ana Martins — Fictícia", cpf: "11144477735",
    email: "ana.martins@consultafacil.demo", perfil: "MEDICO" },
  { _id: ObjectId("600000000000000000000003"), nome: "Paulo Paciente — Fictício", cpf: "39053344705",
    email: "paciente@consultafacil.demo", perfil: "PACIENTE" }
];

// Só cria o usuário se ainda não existir: repetir a carga não troca a senha nem o salt.
usuarios.forEach(({ _id, ...campos }) =>
  db.usuarios.updateOne(
    { _id },
    { $setOnInsert: { ...campos, senha: senhaHash(SENHA_DEMO), ativo: true, criadoEm: agora, atualizadoEm: agora } },
    { upsert: true }
  ));

// Liga o login da médica ao cadastro dela (usado na autorização "só o próprio médico", #10).
db.medicos.updateOne(
  { _id: ObjectId("100000000000000000000001") },
  { $set: { usuarioId: ObjectId("600000000000000000000002") } }
);
