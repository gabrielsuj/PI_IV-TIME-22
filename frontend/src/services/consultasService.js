import { api } from './api';

/** Máximo de consultas devolvidas por GET /consultas/lista (ConsultaLeituraService.LIMITE). */
export const LIMITE_LISTAGEM = 100;

const ouNada = (valor) => valor || undefined;

/**
 * Consultas que o usuário logado pode ver (GET /consultas/lista, #30).
 * periodo: "proximas" (padrão) ou "anteriores". status, medicoId e somenteAtivas são opcionais.
 * Paciente recebe só as próprias e médico só as que atende; quem filtra é o servidor.
 */
export function listarConsultas({ periodo, status, medicoId, somenteAtivas } = {}, { signal } = {}) {
  return api.get('/consultas/lista', {
    params: { periodo: ouNada(periodo), status: ouNada(status), medicoId: ouNada(medicoId), somenteAtivas: ouNada(somenteAtivas) },
    signal,
  });
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

/** Transição autorizada pela API (#28); envia somente o status, sem repetir a escrita automaticamente. */
export function alterarStatusConsulta(id, status) {
  return api.post(`/consultas/${encodeURIComponent(id)}/status`, { status });
}

export const LIMITE_HISTORICO = 20;

/** Uma página do histórico autorizado (#46), ordenada por sequência e sem recuperar pendências no servidor. */
export async function buscarHistoricoConsulta(id, { aposSequencia = 0, limite = LIMITE_HISTORICO } = {}, { signal } = {}) {
  const pagina = await api.get(`/consultas/${encodeURIComponent(id)}/historico`, { params: { aposSequencia, limite }, signal });
  const invalida = () => { throw new Error('Não foi possível ler o histórico recebido. Atualize o histórico para tentar novamente.'); };
  if (!pagina || pagina.consultaId !== id || pagina.aposSequencia !== aposSequencia || pagina.limite !== limite
    || !Array.isArray(pagina.eventos) || pagina.eventos.length > limite || typeof pagina.temMais !== 'boolean'
    || typeof pagina.atualizacaoPendente !== 'boolean'
    || pagina.limiteAtingido !== (pagina.eventos.length === limite)) invalida();
  const ids = new Set();
  let ultima = aposSequencia;
  for (const evento of pagina.eventos) {
    if (typeof evento?.id !== 'string' || !evento.id.trim() || ids.has(evento.id)
      || !Number.isInteger(evento.sequencia) || evento.sequencia <= ultima || evento.sequencia > 2_147_483_647
      || typeof evento.evento !== 'string' || !evento.evento.trim()) invalida();
    ids.add(evento.id);
    ultima = evento.sequencia;
  }
  if (pagina.temMais && (!pagina.limiteAtingido || pagina.eventos.length === 0 || pagina.proximaSequencia !== ultima)) invalida();
  if (!pagina.temMais && pagina.proximaSequencia != null) invalida();
  return pagina;
}
