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

/** Visibilidade na interface; propriedade, prazo e transição continuam validados pelo servidor (#28). */
export function acoesStatusDaConsulta(consulta, perfil, agora) {
  if (!['PACIENTE', 'MEDICO', 'SECRETARIA_ADMIN'].includes(perfil)) return [];
  const acoes = consulta.status === 'AGENDADA' ? ['CONFIRMADA'] : [];
  const fim = Date.parse(consulta.fim);
  if (perfil !== 'PACIENTE' && Number.isFinite(fim) && fim <= agora) {
    if (consulta.status === 'CONFIRMADA') acoes.push('CONCLUIDA');
    if (STATUS_ATIVOS.includes(consulta.status)) acoes.push('FALTA');
  }
  return acoes;
}

/** { data: "21/10/2026", periodo: "09:00 às 09:30" } no fuso da clínica. */
export function dataHoraDaConsulta(consulta) {
  return formatarHorarioAgendamento(consulta, FUSO_CLINICA) ?? { data: '—', periodo: '—' };
}
