/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

// A agenda do dia usa horário local; as próximas consultas usam UTC para exercitar os
// contratos distintos das duas leituras.
export const MEDICO_PAINEL = '000000000000000000000001';
export const OUTRO_MEDICO_PAINEL = '000000000000000000000002';

export const agendaPainel = (medicoId = MEDICO_PAINEL, fusoHorario = 'America/Sao_Paulo') => ({
  id: 'agenda-painel', medicoId, fusoHorario, duracaoMinutos: 30, jornadas: [],
});

export const consultaDia = (extra = {}) => ({
  id: 'consulta-dia', medicoId: MEDICO_PAINEL, pacienteId: 'paciente-dia', pacienteNome: 'Paula da Agenda',
  especialidadeId: 'especialidade', especialidadeNome: 'Clínica Geral',
  inicio: '2026-11-23T09:00', fim: '2026-11-23T09:30', status: 'AGENDADA', ...extra,
});

export const consultaProxima = (extra = {}) => ({
  id: 'consulta-proxima', medicoId: MEDICO_PAINEL, medicoNome: 'Ana Médica',
  pacienteId: 'paciente-proxima', pacienteNome: 'Bruno da Lista',
  especialidadeId: 'especialidade', especialidadeNome: 'Cardiologia',
  inicio: '2026-11-23T13:00:00Z', fim: '2026-11-23T13:30:00Z', status: 'CONFIRMADA', ...extra,
});

export function proximasEmQuantidade(quantidade) {
  return Array.from({ length: quantidade }, (_, i) => {
    const inicio = Date.parse('2026-11-23T12:00:00Z') + i * 1800000;
    return consultaProxima({ id: `consulta-${String(i).padStart(3, '0')}`, pacienteNome: `Paciente ${i}`,
      inicio: new Date(inicio).toISOString(), fim: new Date(inicio + 1800000).toISOString() });
  });
}
