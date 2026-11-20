import { FUSO_CLINICA } from './consulta';
import { DIAS_SEMANA, minutosParaHora } from './horario';

/*
 * Utilitários do calendário operacional (#23).
 * Datas são textos "AAAA-MM-DD" e horários "AAAA-MM-DDTHH:MM" no fuso da agenda,
 * o mesmo formato devolvido pela API. Por isso a comparação entre horários é
 * uma comparação de texto, sem conversão de fuso no navegador.
 */

const ABREVIADOS = ['seg', 'ter', 'qua', 'qui', 'sex', 'sáb', 'dom'];
const MILIS_POR_DIA = 86400000;
const comoData = (data) => new Date(`${data}T00:00:00Z`);

export function somarDias(data, dias) {
  const d = comoData(data);
  d.setUTCDate(d.getUTCDate() + dias);
  return d.toISOString().slice(0, 10);
}

/** 1 = segunda … 7 = domingo (ISO 8601, como na API). */
export function diaIso(data) {
  const d = comoData(data).getUTCDay();
  return d === 0 ? 7 : d;
}

/** Os 7 dias (segunda a domingo) da semana que contém a data. */
export function diasDaSemana(data) {
  const segunda = somarDias(data, 1 - diaIso(data));
  return Array.from({ length: 7 }, (_, i) => somarDias(segunda, i));
}

function formatadorDaData(timeZone) {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit', era: 'short' });
}

function dataCivilEmUtc(instante, formato) {
  const { year, month, day, era } = Object.fromEntries(formato.formatToParts(instante).map(({ type, value }) => [type, value]));
  return new Date(0).setUTCFullYear(era === 'BC' ? 1 - Number(year) : Number(year), Number(month) - 1, Number(day));
}

function inicioDoDia(diaUtc, formato) {
  let inicio = diaUtc - MILIS_POR_DIA;
  let fim = diaUtc + MILIS_POR_DIA;
  // Encontra o primeiro instante dessa data, inclusive quando a mudança de fuso pula a meia-noite.
  while (inicio < fim) {
    const meio = Math.floor((inicio + fim) / 2);
    if (dataCivilEmUtc(meio, formato) < diaUtc) inicio = meio + 1;
    else fim = meio;
  }
  return inicio;
}

/** Dias do calendário no fuso do médico que tocam o dia escolhido no painel. */
export function diasDaAgendaNoDiaClinica(dia, fusoAgenda) {
  if (fusoAgenda === FUSO_CLINICA) return [dia];
  const clinica = formatadorDaData(FUSO_CLINICA);
  const agenda = formatadorDaData(fusoAgenda);
  const diaUtc = comoData(dia).getTime();
  const primeiro = dataCivilEmUtc(inicioDoDia(diaUtc, clinica), agenda);
  const ultimo = dataCivilEmUtc(inicioDoDia(diaUtc + MILIS_POR_DIA, clinica) - 1, agenda);
  return Array.from({ length: (ultimo - primeiro) / MILIS_POR_DIA + 1 }, (_, i) => {
    const data = new Date(primeiro + i * MILIS_POR_DIA).toISOString();
    return data.split('T')[0];
  });
}

/** "2026-11-12" → "qui 12/11" */
export function rotuloDia(data) {
  const [, mes, dia] = data.split('-');
  return `${ABREVIADOS[diaIso(data) - 1]} ${dia}/${mes}`;
}

/** "2026-11-12" → "Quinta-feira, 12/11/2026" */
export function rotuloDiaCompleto(data) {
  const [ano, mes, dia] = data.split('-');
  return `${DIAS_SEMANA[diaIso(data) - 1].nome}, ${dia}/${mes}/${ano}`;
}

/** Horário atual no fuso da agenda, no formato "AAAA-MM-DDTHH:MM". */
export function agoraNoFuso(fuso = 'America/Sao_Paulo') {
  return new Date().toLocaleString('sv-SE', { timeZone: fuso, hour12: false }).slice(0, 16).replace(' ', 'T');
}

const faixasDoDia = (agenda, data) =>
  agenda?.jornadas?.find((j) => Number(j.diaSemana) === diaIso(data))?.faixas ?? [];

const horarioNaData = (data, minuto) =>
  minuto === 1440 ? `${somarDias(data, 1)}T00:00` : `${data}T${minutosParaHora(minuto)}`;

/** Horários de atendimento de um dia, pela jornada e pela duração da agenda. */
export function horariosDoDia(agenda, data) {
  const duracao = Number(agenda?.duracaoMinutos);
  const horarios = [];
  for (const faixa of faixasDoDia(agenda, data)) {
    for (let minuto = Number(faixa.inicioMinuto); minuto + duracao <= Number(faixa.fimMinuto); minuto += duracao) {
      horarios.push({
        hora: minutosParaHora(minuto),
        inicio: horarioNaData(data, minuto),
        fim: horarioNaData(data, minuto + duracao),
      });
    }
  }
  return horarios.sort((a, b) => a.inicio.localeCompare(b.inicio));
}

/** Se dois intervalos { inicio, fim } se sobrepõem (fim exclusivo). */
export const sobrepoe = (a, b) => a.inicio < b.fim && b.inicio < a.fim;

/** Status em que a consulta ainda ocupa a vaga. */
export const STATUS_ATIVOS = ['AGENDADA', 'CONFIRMADA'];

/**
 * Decide como um horário da agenda aparece no calendário (regras definidas para a #23).
 *
 * 1. Horário que já começou: "passado" (indisponível). Leva junto a consulta que existiu
 *    nele, para mostrar se foi concluída, falta ou cancelada.
 * 2. Consulta ativa dentro de um bloqueio: "conflito", para a secretaria remarcar.
 * 3. Consulta ativa: "ocupado".
 * 4. Bloqueio: "bloqueado".
 * 5. Só consulta cancelada: "livre" (a vaga voltou a ficar disponível), com a cancelada junto.
 *
 * @param {{ inicio: string, fim: string }} horario  horário da jornada ("AAAA-MM-DDTHH:MM")
 * @param {Array<{ inicio, fim, status, pacienteNome }>} consultas  do médico no período, em qualquer status
 * @param {Array<{ inicio, fim, motivo }>} bloqueios  do médico no período
 * @param {string} agora  horário atual no mesmo formato
 * @returns {{ estado: 'livre' | 'ocupado' | 'conflito' | 'bloqueado' | 'passado', consulta?: object, bloqueio?: object }}
 */
export function classificarHorario(horario, consultas, bloqueios, agora) {
  const doHorario = consultas.filter((c) => sobrepoe(c, horario));
  const ativa = doHorario.find((c) => STATUS_ATIVOS.includes(c.status));
  const cancelada = doHorario.find((c) => c.status === 'CANCELADA');
  const bloqueio = bloqueios.find((b) => sobrepoe(b, horario));

  if (horario.inicio < agora) {
    const realizada = doHorario.find((c) => c.status !== 'CANCELADA');
    return { estado: 'passado', consulta: realizada ?? cancelada, bloqueio };
  }
  if (ativa && bloqueio) return { estado: 'conflito', consulta: ativa, bloqueio };
  if (ativa) return { estado: 'ocupado', consulta: ativa };
  if (bloqueio) return { estado: 'bloqueado', bloqueio };
  return cancelada ? { estado: 'livre', consulta: cancelada } : { estado: 'livre' };
}

/** Inclui no horário original as consultas futuras que deixaram de caber na jornada. */
export function horariosDoCalendario(agenda, data, consultas, bloqueios, agora) {
  const faixas = faixasDoDia(agenda, data).map(({ inicioMinuto, fimMinuto }) => ({
    inicio: horarioNaData(data, Number(inicioMinuto)),
    fim: horarioNaData(data, Number(fimMinuto)),
  }));
  const horarios = new Map(horariosDoDia(agenda, data).map((horario) => [
    horario.inicio, { ...horario, ...classificarHorario(horario, consultas, bloqueios, agora) },
  ]));

  for (const consulta of consultas) {
    const { inicio, fim, status } = consulta;
    if (!STATUS_ATIVOS.includes(status) || inicio < agora || inicio.slice(0, 10) !== data) continue;
    // A alteração de jornada exige que a consulta inteira caiba em uma única faixa de atendimento.
    if (faixas.some((faixa) => inicio >= faixa.inicio && fim <= faixa.fim)) continue;

    horarios.set(inicio, {
      hora: inicio.slice(11), inicio, fim, estado: 'conflito', foraDaJornada: true, consulta,
      bloqueio: bloqueios.find((bloqueio) => sobrepoe(bloqueio, consulta)),
    });
  }

  return [...horarios.values()].sort((a, b) => a.inicio.localeCompare(b.inicio));
}
