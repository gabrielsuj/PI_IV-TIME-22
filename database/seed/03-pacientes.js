// Pacientes fictícios para testes manuais e apresentações.
// O primeiro cadastro corresponde ao usuário PACIENTE de 02-usuarios.js.
// IDs fixos tornam a carga repetível sem duplicar cadastros.
//
// usuarioId liga o login ao cadastro e permite o paciente agendar sozinho (#21).
// Paulo: login de 02-usuarios.js. Carla e Diego: logins usr(14) e usr(15) de 05-usuarios-equipe.js
// (o vínculo pode ser gravado antes de o login existir, porque os IDs são fixos).
const pacientesDemo = [
  { _id: ObjectId("400000000000000000000001"), nome: "Paulo Paciente — Fictício",
    cpf: "39053344705", email: "paciente@consultafacil.demo",
    usuarioId: ObjectId("600000000000000000000003") },
  { _id: ObjectId("400000000000000000000002"), nome: "Carla Souza — Fictícia",
    cpf: "12345678909", email: "carla.souza@consultafacil.demo",
    usuarioId: ObjectId("60000000000000000000000e") },
  { _id: ObjectId("400000000000000000000003"), nome: "Diego Santos — Fictício",
    cpf: "98765432100", email: "diego.santos@consultafacil.demo",
    usuarioId: ObjectId("60000000000000000000000f") }
];

pacientesDemo.forEach(({ _id, ...campos }) =>
  db.pacientes.updateOne({ _id }, { $set: campos }, { upsert: true }));
