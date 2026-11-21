/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { api } from './api';

/** Consulta o endpoint de saúde da API (GET /health). */
export function getHealth(options) {
  // Propaga as opções de leitura para permitir o cancelamento da verificação pelo
  // componente chamador.
  return api.get('/health', options);
}
