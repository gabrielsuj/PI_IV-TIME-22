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

/** Com { confirmar: true }, salva mesmo deixando consultas marcadas fora da jornada (#44). */
export function salvarAgenda(medicoId, agenda, { confirmar = false } = {}) {
  return api.put('/agendas', agenda, { params: { medicoId, confirmar: confirmar ? 'true' : undefined } });
}

/** Bloqueios do médico que tocam o período [de, ate] (datas AAAA-MM-DD, inclusivas). */
export function listarBloqueios(medicoId, { de, ate, ...options } = {}) {
  return api.get('/bloqueios', { ...options, params: { medicoId, de, ate } });
}

/** Com { confirmar: true }, bloqueia mesmo havendo consultas marcadas no período (#44). */
export function criarBloqueio(bloqueio, { confirmar = false } = {}) {
  return api.post('/bloqueios', bloqueio, { params: { confirmar: confirmar ? 'true' : undefined } });
}

/** Se o erro é o 409 da #44 (consultas afetadas), devolve { mensagem, consultas }; senão, null. */
export function conflitoComConsultas(error) {
  if (!(error instanceof ApiError) || error.status !== 409 || !Array.isArray(error.data?.consultas)) return null;
  return { mensagem: error.message, consultas: error.data.consultas };
}

export function removerBloqueio(id) {
  return api.delete('/bloqueios', { params: { id } });
}