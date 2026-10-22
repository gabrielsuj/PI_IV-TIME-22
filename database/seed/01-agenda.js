const banco = db;

// IDs fixos permitem repetir a carga sem criar duplicatas
function upsert(colecao, docs) {
  docs.forEach(({ _id, ...campos }) =>
    banco.getCollection(colecao).updateOne({ _id }, { $set: campos }, { upsert: true }));
}

const especialidades = [
  {
    _id: ObjectId("000000000000000000000001"),
    codigo: "clinica-geral",
    nome: "Clínica Geral",
    ativa: true
  },
  {
    _id: ObjectId("000000000000000000000002"),
    codigo: "cardiologia",
    nome: "Cardiologia",
    ativa: true
  }
];

upsert("especialidades", especialidades);
upsert("medicos", [
  { _id: ObjectId("100000000000000000000001"), nome: "Ana Martins — Fictícia",
    crmNumero: "000123", crmUf: "SP",
    especialidadeIds: [ObjectId("000000000000000000000001"), ObjectId("000000000000000000000002")],
    ativo: true },
  { _id: ObjectId("100000000000000000000002"), nome: "Bruno Lima — Fictício",
    crmNumero: "004567", crmUf: "SP",
    especialidadeIds: [ObjectId("000000000000000000000001")], ativo: true }
]);

upsert("agendas", [
  { _id: ObjectId("200000000000000000000001"), medicoId: ObjectId("100000000000000000000001"),
    fusoHorario: "America/Sao_Paulo", duracaoMinutos: 30,
    jornadas: [
      { diaSemana: 1, faixas: [{ inicioMinuto: 480, fimMinuto: 720 }, { inicioMinuto: 780, fimMinuto: 1020 }] },
      { diaSemana: 3, faixas: [{ inicioMinuto: 480, fimMinuto: 720 }] }
    ] },
  { _id: ObjectId("200000000000000000000002"), medicoId: ObjectId("100000000000000000000002"),
    fusoHorario: "America/Sao_Paulo", duracaoMinutos: 20,
    jornadas: [
      { diaSemana: 2, faixas: [{ inicioMinuto: 840, fimMinuto: 1080 }] },
      { diaSemana: 4, faixas: [{ inicioMinuto: 840, fimMinuto: 1080 }] }
    ] }
]);

upsert("bloqueios", [
  { _id: ObjectId("300000000000000000000001"), medicoId: ObjectId("100000000000000000000001"),
    inicio: new Date("2026-10-26T12:00:00Z"),   // 09:00 em São Paulo
    fim:    new Date("2026-10-26T13:00:00Z"),   // 10:00 em São Paulo
    motivo: "Compromisso pessoal — exemplo fictício" }
]);
