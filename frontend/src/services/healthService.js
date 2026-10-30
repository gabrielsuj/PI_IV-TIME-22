import { api } from './api';

/** Consulta o endpoint de saúde da API (GET /health). */
export function getHealth(options) {
  return api.get('/health', options);
}
