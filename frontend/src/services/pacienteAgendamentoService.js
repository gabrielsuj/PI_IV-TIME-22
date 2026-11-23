/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { api } from './api';

export async function buscarPacientesAgendamento(busca = '', { signal } = {}) {
  const pacientes = await api.get('/pacientes', { params: { busca: busca.trim() }, signal });
  // Uma resposta fora do contrato é tratada como falha, distinguindo erro de leitura
  // de uma busca válida sem pacientes.
  if (!Array.isArray(pacientes)) {
    throw new Error('Não foi possível ler os pacientes. Tente novamente.');
  }
  return pacientes;
}
