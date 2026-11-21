/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { api } from './api';

/** Consultas do médico no período (datas AAAA-MM-DD, inclusivas), em qualquer status. #23 */
export function listarConsultas(medicoId, { de, ate, fusoHorario, ...options } = {}) {
  // O fuso opcional acompanha as datas para que o painel consulte o dia da clínica sem
  // mudar o padrão das outras chamadas.
  return api.get('/consultas', { ...options, params: { medicoId, de, ate, fusoHorario } });
}
