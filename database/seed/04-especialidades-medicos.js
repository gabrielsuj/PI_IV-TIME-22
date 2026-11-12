// Carga ampliada: especialidades, corpo clínico, agendas semanais e bloqueios.
// Complementa 01-agenda.js (Clínica Geral, Cardiologia, Ana e Bruno), sem alterá-lo.
//
// Todos os dados são fictícios. IDs fixos permitem repetir a carga sem duplicar.
// Os bloqueios usam datas relativas ao dia da carga, para que a demonstração
// sempre tenha bloqueios no passado recente e nas próximas semanas.
const FUSO_MINUTOS = 180; // America/Sao_Paulo = UTC-3 (sem horário de verão desde 2019)

function upsert(colecao, docs) {
  docs.forEach(({ _id, ...campos }) =>
    db.getCollection(colecao).updateOne({ _id }, { $set: campos }, { upsert: true }));
}
const esp = (n) => ObjectId("00000000000000000000000" + n.toString(16));
const med = (n) => ObjectId("1000000000000000000000" + n.toString(16).padStart(2, "0"));

// ---------- Especialidades ----------
upsert("especialidades", [
  { _id: esp(3), codigo: "pediatria", nome: "Pediatria", ativa: true },
  { _id: esp(4), codigo: "dermatologia", nome: "Dermatologia", ativa: true },
  { _id: esp(5), codigo: "ortopedia", nome: "Ortopedia e Traumatologia", ativa: true },
  { _id: esp(6), codigo: "ginecologia-obstetricia", nome: "Ginecologia e Obstetrícia", ativa: true },
  { _id: esp(7), codigo: "endocrinologia", nome: "Endocrinologia e Metabologia", ativa: true },
  { _id: esp(8), codigo: "oftalmologia", nome: "Oftalmologia", ativa: true },
  { _id: esp(9), codigo: "acupuntura", nome: "Acupuntura", ativa: false } // desativada: mostra o filtro de status
]);

// ---------- Corpo clínico ----------
upsert("medicos", [
  { _id: med(3), nome: "Carolina Mendes", crmNumero: "145872", crmUf: "SP", especialidadeIds: [esp(3)], ativo: true },
  { _id: med(4), nome: "Rafael Duarte", crmNumero: "132904", crmUf: "SP", especialidadeIds: [esp(2)], ativo: true },
  { _id: med(5), nome: "Juliana Ferraz", crmNumero: "158310", crmUf: "SP", especialidadeIds: [esp(4)], ativo: true },
  { _id: med(6), nome: "Marcelo Tavares", crmNumero: "121477", crmUf: "SP", especialidadeIds: [esp(5)], ativo: true },
  { _id: med(7), nome: "Patrícia Nogueira", crmNumero: "139065", crmUf: "SP", especialidadeIds: [esp(6)], ativo: true },
  { _id: med(8), nome: "Eduardo Prado", crmNumero: "167223", crmUf: "SP", especialidadeIds: [esp(7), esp(1)], ativo: true },
  { _id: med(9), nome: "Fernanda Lopes", crmNumero: "054318", crmUf: "MG", especialidadeIds: [esp(8)], ativo: true },
  { _id: med(10), nome: "Gustavo Ribeiro", crmNumero: "172590", crmUf: "SP", especialidadeIds: [esp(1)], ativo: true },
  // Inativa (licença): aparece no cadastro, mas sem agenda nem consultas.
  { _id: med(11), nome: "Helena Castro", crmNumero: "088412", crmUf: "RJ", especialidadeIds: [esp(3)], ativo: false }
]);

// ---------- Agendas semanais (diaSemana ISO: 1 = segunda ... 7 = domingo) ----------
const h = (hora, minuto = 0) => NumberInt(hora * 60 + minuto);
const faixa = (de, ate) => ({ inicioMinuto: de, fimMinuto: ate });
const agenda = (n, medico, duracao, jornadas) => ({
  _id: ObjectId("2000000000000000000000" + n.toString(16).padStart(2, "0")),
  medicoId: medico, fusoHorario: "America/Sao_Paulo", duracaoMinutos: NumberInt(duracao),
  jornadas: jornadas.map(([dia, ...faixas]) => ({ diaSemana: NumberInt(dia), faixas }))
});
const manha = faixa(h(8), h(12));
upsert("agendas", [
  agenda(3, med(3), 20, [[1, manha], [2, faixa(h(13), h(17))], [3, manha], [5, manha]]),
  agenda(4, med(4), 30, [[2, manha, faixa(h(14), h(18))], [4, manha, faixa(h(14), h(18))]]),
  agenda(5, med(5), 20, [[1, faixa(h(13), h(19))], [3, faixa(h(13), h(19))], [5, manha]]),
  agenda(6, med(6), 30, [[1, manha], [3, faixa(h(14), h(18))], [4, manha]]),
  agenda(7, med(7), 40, [[2, faixa(h(13, 20), h(18, 40))], [4, faixa(h(13, 20), h(18, 40))], [6, manha]]),
  agenda(8, med(8), 30, [[1, faixa(h(14), h(18))], [3, faixa(h(14), h(18))], [5, faixa(h(14), h(18))]]),
  agenda(9, med(9), 15, [[2, manha], [3, manha], [4, manha]]),
  agenda(10, med(10), 20, [[1, faixa(h(7), h(11))], [2, faixa(h(7), h(11))], [3, faixa(h(7), h(11))],
    [4, faixa(h(7), h(11))], [5, faixa(h(7), h(11))]])
]);

// ---------- Bloqueios (datas relativas ao dia da carga) ----------
const agoraLocal = new Date(Date.now() - FUSO_MINUTOS * 60000);
const hojeUtc = Date.UTC(agoraLocal.getUTCFullYear(), agoraLocal.getUTCMonth(), agoraLocal.getUTCDate());
// Data/hora local (dia relativo a hoje, minutos do dia) -> instante UTC
const quando = (dias, minutos = 0) => new Date(hojeUtc + dias * 86400000 + (minutos + FUSO_MINUTOS) * 60000);
const diaIso = (dias) => { const d = new Date(hojeUtc + dias * 86400000).getUTCDay(); return d === 0 ? 7 : d; };
const proximaSegunda = ((8 - diaIso(0)) % 7) || 7;

// Ancorados na semana para cair sempre em dias de atendimento de cada médico,
// qualquer que seja o dia da carga (0 = segunda desta semana).
const segunda = proximaSegunda - 7;
upsert("bloqueios", [
  { _id: ObjectId("300000000000000000000002"), medicoId: med(2), // Bruno (ter/qui): semana seguinte de férias
    inicio: quando(segunda + 7), fim: quando(segunda + 12), motivo: "Férias" },
  { _id: ObjectId("300000000000000000000003"), medicoId: med(4), // Rafael (ter/qui): ter a qui da semana seguinte
    inicio: quando(segunda + 8), fim: quando(segunda + 11), motivo: "Congresso de Cardiologia" },
  { _id: ObjectId("300000000000000000000004"), medicoId: med(3), // Carolina (qua de manhã): quarta da semana seguinte
    inicio: quando(segunda + 9, 8 * 60), fim: quando(segunda + 9, 10 * 60), motivo: "Reunião clínica" },
  { _id: ObjectId("300000000000000000000005"), medicoId: med(6), // Marcelo (qui de manhã): quinta da semana passada
    inicio: quando(segunda - 4), fim: quando(segunda - 3), motivo: "Plantão hospitalar" },
  { _id: ObjectId("300000000000000000000006"), medicoId: med(9), // Fernanda (ter a qui): daqui a duas semanas
    inicio: quando(segunda + 15), fim: quando(segunda + 18), motivo: "Curso de atualização" }
]);

print("OK carga 04: " + db.especialidades.countDocuments() + " especialidades, " +
  db.medicos.countDocuments() + " médicos, " + db.agendas.countDocuments() + " agendas, " +
  db.bloqueios.countDocuments() + " bloqueios");
