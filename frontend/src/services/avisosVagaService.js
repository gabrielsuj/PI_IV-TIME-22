/*
Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

import { api } from './api';

export function listarAvisosVaga({ signal } = {}) {
  return api.get('/avisos-vaga', { signal });
}

export function agendarPeloAviso(avisoId) {
  return api.post('/avisos-vaga', { avisoId });
}
