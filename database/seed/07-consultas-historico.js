// Consultas e histórico de demonstração, coerentes com agendas, bloqueios e logins.
//
// Janela: 3 semanas antes e 3 semanas depois do dia da carga. Consultas passadas
// terminam como CONCLUIDA, FALTA ou CANCELADA; futuras ficam AGENDADA, CONFIRMADA
// ou CANCELADA. Cada consulta tem os eventos de histórico na mesma forma gravada por
// RegistroConsultaService (sequência 1..n, ator com perfil e id do usuário) e
// `versao` igual à última sequência, sem eventos pendentes.
//
// Regras respeitadas: horários dentro da jornada e da duração de cada agenda,
// fora dos bloqueios, sem sobreposição de médico nem de paciente, especialidade
// entre as do médico. Semente fixa: a distribuição é sempre a mesma, relativa à data.
//
// Repetível: apaga e recria apenas as consultas desta carga (IDs 70…) e o histórico delas.
const FUSO_MINUTOS = 180;          // America/Sao_Paulo = UTC-3
const DIAS_ANTES = 21, DIAS_DEPOIS = 21;
const MIN = 60000, HORA = 60 * MIN, DIA = 24 * HORA;

function gerador(semente) { // mulberry32
  let a = semente >>> 0;
  return () => {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = gerador(4242);
const escolher = (lista) => lista[Math.floor(rand() * lista.length)];

const agora = Date.now();
const agoraLocal = new Date(agora - FUSO_MINUTOS * MIN);
const hojeUtc = Date.UTC(agoraLocal.getUTCFullYear(), agoraLocal.getUTCMonth(), agoraLocal.getUTCDate());
const quando = (dias, minutos) => hojeUtc + dias * DIA + (minutos + FUSO_MINUTOS) * MIN; // ms UTC
const diaIso = (dias) => { const d = new Date(hojeUtc + dias * DIA).getUTCDay(); return d === 0 ? 7 : d; };

// ---------- Limpeza (somente o que esta carga criou) ----------
const C0 = ObjectId("700000000000000000000000"), C1 = ObjectId("710000000000000000000000");
db.historico.deleteMany({ consultaId: { $gte: C0, $lt: C1 } });
db.consultas.deleteMany({ _id: { $gte: C0, $lt: C1 } });
const idConsulta = (n) => ObjectId("7" + n.toString(16).padStart(23, "0"));

// ---------- Dados de referência ----------
const usuarios = db.usuarios.find({ ativo: true }).toArray();
const secretarias = usuarios.filter((u) => u.perfil === "SECRETARIA_ADMIN").map((u) => u._id);
const loginPorEmail = new Map(usuarios.filter((u) => u.perfil === "PACIENTE").map((u) => [u.email, u._id]));
const pacientes = db.pacientes.find().sort({ _id: 1 }).toArray();
const PAULO = ObjectId("400000000000000000000001"); // paciente@consultafacil.demo
const sorteaveis = pacientes.filter((p) => !p._id.equals(PAULO));

const medicos = db.medicos.find({ ativo: true }).toArray();
const agendaPorMedico = new Map(db.agendas.find().toArray().map((a) => [a.medicoId.toHexString(), a]));
const bloqueios = db.bloqueios.find().toArray();

// Consultas ativas criadas fora desta carga (pela aplicação) continuam ocupando o horário.
const ocupado = new Set(), pacienteNoDia = new Set();
db.consultas.find({ status: { $in: ["AGENDADA", "CONFIRMADA"] } }).forEach((c) => {
  ocupado.add(c.medicoId.toHexString() + c.inicio.getTime());
  ocupado.add(c.pacienteId.toHexString() + c.inicio.getTime());
});

// ---------- 1. Escolher horários, pacientes e status ----------
function ocupacao(inicio, dias) { // parte da agenda preenchida
  if (inicio < agora) return 0.5;
  if (dias <= 7) return 0.5;
  return dias <= 14 ? 0.3 : 0.15;
}
function statusPara(inicio, fim) {
  const r = rand();
  if (fim <= agora) return r < 0.74 ? "CONCLUIDA" : r < 0.83 ? "FALTA" : "CANCELADA";
  if (r < 0.1) return "CANCELADA";
  const confirma = inicio - agora < 2 * DIA ? 0.7 : 0.25;
  return rand() < confirma ? "CONFIRMADA" : "AGENDADA";
}

const planos = [];
for (let dias = -DIAS_ANTES; dias <= DIAS_DEPOIS; dias++) {
  for (const medico of medicos) {
    const agenda = agendaPorMedico.get(medico._id.toHexString());
    if (!agenda) continue;
    const jornada = agenda.jornadas.find((j) => Number(j.diaSemana) === diaIso(dias));
    if (!jornada) continue;
    const duracao = Number(agenda.duracaoMinutos);
    const bloqueiosDoMedico = bloqueios.filter((b) => b.medicoId.equals(medico._id));
    for (const f of jornada.faixas) {
      for (let m = Number(f.inicioMinuto); m + duracao <= Number(f.fimMinuto); m += duracao) {
        const inicio = quando(dias, m), fim = inicio + duracao * MIN;
        if (bloqueiosDoMedico.some((b) => b.inicio.getTime() < fim && b.fim.getTime() > inicio)) continue;
        if (ocupado.has(medico._id.toHexString() + inicio)) continue;
        if (rand() > ocupacao(inicio, dias)) continue;
        // Pacientes frequentes aparecem mais (distribuição enviesada para o início da lista).
        let paciente = null;
        for (let tentativa = 0; tentativa < 12 && !paciente; tentativa++) {
          const p = sorteaveis[Math.floor(Math.pow(rand(), 1.4) * sorteaveis.length)];
          if (!pacienteNoDia.has(p._id.toHexString() + dias) && !ocupado.has(p._id.toHexString() + inicio)) paciente = p;
        }
        if (!paciente) continue;
        pacienteNoDia.add(paciente._id.toHexString() + dias);
        const especialidadeId = medico.especialidadeIds.length > 1 && rand() < 0.3
          ? medico.especialidadeIds[1] : medico.especialidadeIds[0];
        planos.push({ medico, paciente, especialidadeId, inicio, fim, dias, status: statusPara(inicio, fim) });
      }
    }
  }
}

// ---------- 2. Paulo (login de paciente da demo) com um histórico pensado ----------
const diasDoPaulo = new Set();
const PEDIATRIA = ObjectId("000000000000000000000003");
function darAoPaulo(filtro) { // Paulo é adulto: nunca recebe consulta de pediatria
  const plano = planos.find((p) => filtro(p) && !p.especialidadeId.equals(PEDIATRIA) && !diasDoPaulo.has(p.dias));
  if (!plano) return;
  pacienteNoDia.delete(plano.paciente._id.toHexString() + plano.dias);
  plano.paciente = pacientes.find((p) => p._id.equals(PAULO));
  diasDoPaulo.add(plano.dias);
}
const deClinica = (p) => p.especialidadeId.equals(ObjectId("000000000000000000000001"));
darAoPaulo((p) => p.status === "CONCLUIDA" && deClinica(p) && p.dias <= -14);
darAoPaulo((p) => p.status === "CONCLUIDA" && p.dias > -14 && p.dias <= -3);
darAoPaulo((p) => p.status === "CANCELADA" && p.dias < 0);
darAoPaulo((p) => p.status === "CONFIRMADA" && p.dias >= 1 && p.dias <= 6);
darAoPaulo((p) => p.status === "AGENDADA" && p.dias >= 8);

// ---------- 3. Eventos de histórico ----------
const MOTIVOS_PACIENTE = ["Paciente solicitou remarcação", "Imprevisto pessoal do paciente",
  "Conflito de horário do paciente", "Paciente não poderá comparecer"];
const MOTIVOS_CLINICA = ["Indisponibilidade do médico", "Reorganização da agenda pela clínica"];

function horario(a, b) { // instante entre a e b, preferindo horário comercial local
  if (b <= a) return a;
  let t = a + rand() * (b - a);
  const local = new Date(t - FUSO_MINUTOS * MIN);
  if (local.getUTCHours() < 8 || local.getUTCHours() >= 19) {
    const dia = Date.UTC(local.getUTCFullYear(), local.getUTCMonth(), local.getUTCDate());
    const alvo = dia + (8 * 60 + Math.floor(rand() * 660) + FUSO_MINUTOS) * MIN;
    if (alvo >= a && alvo <= b) t = alvo;
  }
  return Math.max(a, Math.floor(t / MIN) * MIN);
}

const consultas = [], historico = [];
planos.sort((x, y) => x.inicio - y.inicio || x.medico._id.toHexString().localeCompare(y.medico._id.toHexString()));
planos.forEach((p, i) => {
  const _id = idConsulta(i + 1);
  const loginPaciente = loginPorEmail.get(p.paciente.email) || null;
  const secretaria = () => ({ perfil: "SECRETARIA_ADMIN", id: escolher(secretarias) });
  const medicoAtor = () => (p.medico.usuarioId ? { perfil: "MEDICO", id: p.medico.usuarioId } : secretaria());
  const pacienteAtor = () => (loginPaciente ? { perfil: "PACIENTE", id: loginPaciente } : secretaria());

  const eventos = [];
  let ultimo = 0;
  const registrar = (evento, statusAnterior, statusNovo, ator, instante, motivo) => {
    instante = Math.min(agora, Math.max(instante, ultimo + MIN));
    ultimo = instante;
    const e = { _id: new ObjectId(), consultaId: _id, sequencia: NumberInt(eventos.length + 1), evento,
      statusAnterior, statusNovo, pacienteId: p.paciente._id, medicoId: p.medico._id,
      especialidadeId: p.especialidadeId, dataHoraConsulta: new Date(p.inicio), fimConsulta: new Date(p.fim),
      ator, ocorridoEm: new Date(instante) };
    if (motivo) e.motivo = motivo;
    eventos.push(e);
  };

  // Criação: de 1 a 21 dias antes, nunca depois de agora.
  const criadaEm = horario(Math.max(p.inicio - 21 * DIA, hojeUtc - 35 * DIA), Math.min(p.inicio - 2 * HORA, agora - 10 * MIN));
  registrar("CRIACAO", null, "AGENDADA", loginPaciente && rand() < 0.45 ? pacienteAtor() : secretaria(), criadaEm);
  let status = "AGENDADA";

  const confirmar = { CONCLUIDA: 0.85, FALTA: 0.5, CANCELADA: 0.35, CONFIRMADA: 1, AGENDADA: 0 }[p.status];
  // Confirmação nas 72 h anteriores; para consultas mais distantes, em algum momento até agora.
  const inicioConfirmacao = Math.max(ultimo + 30 * MIN, Math.min(p.inicio - 72 * HORA, agora - 6 * HORA));
  const fimConfirmacao = Math.min(p.inicio - HORA, agora);
  if (rand() < confirmar && inicioConfirmacao < fimConfirmacao) {
    const ator = loginPaciente && rand() < 0.6 ? pacienteAtor() : rand() < 0.85 ? secretaria() : medicoAtor();
    registrar("CONFIRMACAO", "AGENDADA", "CONFIRMADA", ator, horario(inicioConfirmacao, fimConfirmacao));
    status = "CONFIRMADA";
  }

  let motivoCancelamento = null;
  if (p.status === "CONCLUIDA") {
    registrar("CONCLUSAO", status, "CONCLUIDA", rand() < 0.9 ? medicoAtor() : secretaria(), p.fim + Math.floor(rand() * 15) * MIN);
  } else if (p.status === "FALTA") {
    registrar("FALTA", status, "FALTA", rand() < 0.7 ? secretaria() : medicoAtor(), p.fim + (10 + Math.floor(rand() * 30)) * MIN);
  } else if (p.status === "CANCELADA") {
    const pelaClinica = rand() < 0.25;
    motivoCancelamento = escolher(pelaClinica ? MOTIVOS_CLINICA : MOTIVOS_PACIENTE);
    const ator = pelaClinica ? (rand() < 0.5 ? medicoAtor() : secretaria()) : (rand() < 0.6 ? pacienteAtor() : secretaria());
    registrar("CANCELAMENTO", status, "CANCELADA", ator,
      horario(ultimo + 30 * MIN, Math.min(p.inicio - HORA, agora)), motivoCancelamento);
  }

  // O status gravado é sempre o do último evento: histórico e consulta nunca divergem.
  const consulta = { _id, pacienteId: p.paciente._id, medicoId: p.medico._id, especialidadeId: p.especialidadeId,
    inicio: new Date(p.inicio), fim: new Date(p.fim), status: eventos[eventos.length - 1].statusNovo,
    criadaEm: eventos[0].ocorridoEm, versao: NumberInt(eventos.length), eventosPendentes: [] };
  if (eventos.length > 1) consulta.atualizadaEm = eventos[eventos.length - 1].ocorridoEm;
  if (motivoCancelamento) consulta.motivoCancelamento = motivoCancelamento;
  consultas.push(consulta);
  historico.push(...eventos);
});

if (consultas.length) db.consultas.insertMany(consultas);
if (historico.length) db.historico.insertMany(historico);

const porStatus = db.consultas.aggregate([{ $group: { _id: "$status", n: { $sum: 1 } } }, { $sort: { _id: 1 } }])
  .toArray().map((s) => s._id + " " + s.n).join(", ");
print("OK carga 07: " + consultas.length + " consultas (" + porStatus + "), " + historico.length + " eventos de histórico");
