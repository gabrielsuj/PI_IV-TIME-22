import { api } from './api';

/** Máximo de consultas devolvidas por GET /consultas/lista (ConsultaLeituraService.LIMITE). */
export const LIMITE_LISTAGEM = 100;

const ouNada = (valor) => valor || undefined;

/**
 * Consultas que o usuário logado pode ver (GET /consultas/lista, #30).
 * periodo: "proximas" (padrão) ou "anteriores"; status e medicoId são opcionais.
 * Paciente recebe só as próprias e médico só as que atende; quem filtra é o servidor.
 */
export function listarConsultas({ periodo, status, medicoId } = {}, { signal } = {}) {
  return api.get('/consultas/lista', { params: { periodo: ouNada(periodo), status: ouNada(status), medicoId: ouNada(medicoId) }, signal });
}

/** Detalhe de uma consulta (GET /consultas/{id}, #30), com nomes e a flag podeCancelar. */
export function buscarConsulta(id, { signal } = {}) {
  return api.get(`/consultas/${encodeURIComponent(id)}`, { signal });
}

/**
 * Cancela a consulta (POST /consultas/{id}/cancelamento, #27). Motivo opcional, até 255 caracteres.
 * 409 = a consulta já foi finalizada ou já começou; a tela recarrega o detalhe.
 */
export function cancelarConsulta(id, motivo) {
  const texto = motivo?.trim();
  return api.post(`/consultas/${encodeURIComponent(id)}/cancelamento`, texto ? { motivo: texto } : {});
}
