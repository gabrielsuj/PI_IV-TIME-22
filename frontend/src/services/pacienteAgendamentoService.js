import { api } from './api';

export async function buscarPacientesAgendamento(busca = '', { signal } = {}) {
  const pacientes = await api.get('/pacientes', { params: { busca: busca.trim() }, signal });
  if (!Array.isArray(pacientes)) {
    throw new Error('Não foi possível ler os pacientes. Tente novamente.');
  }
  return pacientes;
}
