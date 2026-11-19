import { api } from './api';

export function listarAvisosVaga({ signal } = {}) {
  return api.get('/avisos-vaga', { signal });
}

export function agendarPeloAviso(avisoId) {
  return api.post('/avisos-vaga', { avisoId });
}
