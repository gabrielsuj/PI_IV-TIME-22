import { api } from './api';

/** Consultas do médico no período (datas AAAA-MM-DD, inclusivas), em qualquer status. #23 */
export function listarConsultas(medicoId, { de, ate, fusoHorario, ...options } = {}) {
  return api.get('/consultas', { ...options, params: { medicoId, de, ate, fusoHorario } });
}
