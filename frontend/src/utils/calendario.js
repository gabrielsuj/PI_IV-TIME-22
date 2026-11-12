import { DIAS_SEMANA, minutosParaHora } from './horario';

/*
 * Utilitários do calendário operacional (#23).
 * Datas são textos "AAAA-MM-DD" e horários "AAAA-MM-DDTHH:MM" no fuso da agenda,
 * o mesmo formato devolvido pela API. Por isso a comparação entre horários é
 * uma comparação de texto, sem conversão de fuso no navegador.
 */

const ABREVIADOS = ['seg', 'ter', 'qua', 'qui', 'sex', 'sáb', 'dom'];
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

/** Horários de atendimento de um dia, pela jornada e pela duração da agenda. */
export function horariosDoDia(agenda, data) {
  const jornada = agenda?.jornadas?.find((j) => Number(j.diaSemana) === diaIso(data));
  if (!jornada) return [];
  const duracao = Number(agenda.duracaoMinutos);
  const horarios = [];
  for (const faixa of jornada.faixas) {
    for (let minuto = Number(faixa.inicioMinuto); minuto + duracao <= Number(faixa.fimMinuto); minuto += duracao) {
      const fim = minuto + duracao;
      horarios.push({
        hora: minutosParaHora(minuto),
        inicio: `${data}T${minutosParaHora(minuto)}`,
        fim: fim >= 1440 ? `${somarDias(data, 1)}T00:00` : `${data}T${minutosParaHora(fim)}`,
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
