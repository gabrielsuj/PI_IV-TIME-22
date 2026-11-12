import { api } from './api';

/**
 * Horários livres de um médico em uma data (GET /vagas, #21).
 * Resposta: { medicoId, fusoHorario, semAgenda, horarios: [{ inicio, fim }] }, com horários em UTC.
 */
export function buscarVagas({ especialidadeId, medicoId, data }, { signal } = {}) {
  return api.get('/vagas', { params: { especialidadeId, medicoId, data }, signal });
}

/**
 * Confirma o agendamento (POST /consultas, #21).
 * dados: { especialidadeId, medicoId, inicio, fim } e, para a secretaria, pacienteId.
 * Erro 409 = o horário deixou de estar livre; a tela atualiza a lista de horários.
 */
export function confirmarAgendamento(dados) {
  return api.post('/consultas', dados);
}
