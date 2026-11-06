import { api, ApiError } from './api';

/** Agenda semanal do médico, ou null quando ainda não foi configurada. */
export async function buscarAgenda(medicoId, options) {
  try {
    return await api.get('/agendas', { ...options, params: { medicoId } });
  } catch (error) {
    if (error instanceof ApiError && error.status === 404 && /^Agenda/.test(error.message)) return null;
    throw error;
  }
}

export function salvarAgenda(medicoId, agenda) {
  return api.put('/agendas', agenda, { params: { medicoId } });
}

/** Bloqueios do médico que tocam o período [de, ate] (datas AAAA-MM-DD, inclusivas). */
export function listarBloqueios(medicoId, { de, ate, ...options } = {}) {
  return api.get('/bloqueios', { ...options, params: { medicoId, de, ate } });
}

export function criarBloqueio(bloqueio) {
  return api.post('/bloqueios', bloqueio);
}

export function removerBloqueio(id) {
  return api.delete('/bloqueios', { params: { id } });
}