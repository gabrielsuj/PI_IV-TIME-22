import { formatarHorarioAgendamento } from './horarioAgendamento';

/** Fuso da clínica: a API devolve horários em UTC e as telas exibem neste fuso (mesmo padrão das agendas). */
export const FUSO_CLINICA = 'America/Sao_Paulo';

/** Rótulo e cor (tom do Badge) de cada status da consulta. */
export const STATUS_CONSULTA = {
  AGENDADA: { rotulo: 'Agendada', tom: 'info' },
  CONFIRMADA: { rotulo: 'Confirmada', tom: 'teal' },
  CONCLUIDA: { rotulo: 'Concluída', tom: 'success' },
  FALTA: { rotulo: 'Falta', tom: 'neutral' },
  CANCELADA: { rotulo: 'Cancelada', tom: 'danger' },
};

export const STATUS_ATIVOS = ['AGENDADA', 'CONFIRMADA'];

/** { data: "21/10/2026", periodo: "09:00 às 09:30" } no fuso da clínica. */
export function dataHoraDaConsulta(consulta) {
  return formatarHorarioAgendamento(consulta, FUSO_CLINICA) ?? { data: '—', periodo: '—' };
}
