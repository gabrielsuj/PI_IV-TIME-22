import { api } from './api';
/**
 * @author Murilo Rigoni (criador)
 */

// Contrato: backend/.../api/handler/FilaEsperaHandler.java
// GET sem filtro de status retorna apenas AGUARDANDO e SINALIZADA.
export function listarFilaEspera(filtros = {}, options = {}) {
  return api.get('/fila-espera', { ...options, params: filtros });
}

export function inscreverFilaEspera(dados, options = {}) {
  return api.post('/fila-espera', dados, options);
}

export function cancelarInscricaoFila(id, options = {}) {
  return api.delete(`/fila-espera/${encodeURIComponent(id)}`, options);
}
