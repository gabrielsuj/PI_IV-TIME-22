// Pacientes fictícios para testes manuais e apresentações.
// O primeiro cadastro corresponde ao usuário PACIENTE de 02-usuarios.js.
// IDs fixos tornam a carga repetível sem duplicar cadastros.
const pacientesDemo = [
  { _id: ObjectId("400000000000000000000001"), nome: "Paulo Paciente — Fictício",
    cpf: "39053344705", email: "paciente@consultafacil.demo" },
  { _id: ObjectId("400000000000000000000002"), nome: "Carla Souza — Fictícia",
    cpf: "12345678909", email: "carla.souza@consultafacil.demo" },
  { _id: ObjectId("400000000000000000000003"), nome: "Diego Santos — Fictício",
    cpf: "98765432100", email: "diego.santos@consultafacil.demo" }
];

pacientesDemo.forEach(({ _id, ...campos }) =>
  db.pacientes.updateOne({ _id }, { $set: campos }, { upsert: true }));
