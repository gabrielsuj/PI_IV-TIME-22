/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { useEffect, useRef, useState } from 'react';
import { buscarHistoricoConsulta } from '../services/consultasService';

// A atualização automática tem limite para não consultar indefinidamente uma projeção
// pendente; a tentativa manual permanece disponível.
const MAX_ATUALIZACOES_AUTOMATICAS = 3;
const INTERVALO_ATUALIZACAO = 2000;

/** Estado independente do resumo: novas tentativas repetem só o GET da página que falhou. */
export function useHistoricoConsulta(consultaId) {
  const [pedido, setPedido] = useState({ aposSequencia: 0, revisao: 0, tentativas: 0, automatico: false });
  const [estado, setEstado] = useState({ pedido: null, eventos: [], pagina: null, erro: null, primeiroNovoId: null, revisitarInicio: false });
  const eventosAtuais = useRef([]);
  const pendenciaObservada = useRef(false);
  const emLeitura = useRef(true);
  const carregando = estado.pedido !== pedido;

  useEffect(() => {
    const controller = new AbortController();
    buscarHistoricoConsulta(consultaId, { aposSequencia: pedido.aposSequencia }, { signal: controller.signal })
      .then((pagina) => {
        if (controller.signal.aborted) return;
        if (pedido.aposSequencia > 0 && pagina.eventos.some((evento) => eventosAtuais.current.some((anterior) => anterior.id === evento.id)))
          throw new Error('O histórico recebido contém eventos repetidos. Atualize o histórico para conferir os dados.');
        const eventos = pedido.aposSequencia === 0 ? pagina.eventos : [...eventosAtuais.current, ...pagina.eventos];
        eventosAtuais.current = eventos;
        pendenciaObservada.current = pagina.atualizacaoPendente || (pedido.aposSequencia > 0 && pendenciaObservada.current);
        setEstado({ pedido, eventos, pagina, erro: null, primeiroNovoId: pedido.aposSequencia > 0 ? pagina.eventos[0]?.id : null,
          revisitarInicio: pedido.aposSequencia > 0 && pendenciaObservada.current });
      })
      .catch((erro) => {
        if (controller.signal.aborted) return;
        const acessoRecusado = [400, 403, 404].includes(erro.status);
        if (acessoRecusado) { eventosAtuais.current = []; pendenciaObservada.current = false; }
        setEstado((anterior) => ({ ...anterior, pedido, erro, primeiroNovoId: null,
          ...(acessoRecusado ? { eventos: [], pagina: null, revisitarInicio: false } : {}) }));
      })
      .finally(() => { if (!controller.signal.aborted) emLeitura.current = false; });
    return () => controller.abort();
  }, [consultaId, pedido]);

  useEffect(() => {
    if (carregando || estado.erro || !estado.pagina?.atualizacaoPendente
      || pedido.aposSequencia !== 0 || pedido.tentativas >= MAX_ATUALIZACOES_AUTOMATICAS) return;
    const timer = setTimeout(() => {
      if (emLeitura.current) return;
      emLeitura.current = true;
      setPedido((anterior) => ({ ...anterior, revisao: anterior.revisao + 1,
        tentativas: anterior.tentativas + 1, automatico: true }));
    }, INTERVALO_ATUALIZACAO);
    return () => clearTimeout(timer);
  }, [carregando, estado.erro, estado.pagina, pedido]);

  function solicitar(aposSequencia) {
    if (emLeitura.current || carregando) return;
    emLeitura.current = true;
    setPedido((anterior) => ({ aposSequencia, revisao: anterior.revisao + 1, tentativas: 0, automatico: false }));
  }

  return { ...estado, carregando, erro: carregando ? null : estado.erro, leituraAutomatica: pedido.automatico,
    atualizacaoPausada: estado.revisitarInicio || pedido.aposSequencia > 0 || pedido.tentativas >= MAX_ATUALIZACOES_AUTOMATICAS,
    atualizar: () => solicitar(0), tentarNovamente: () => solicitar(pedido.aposSequencia),
    carregarMais: () => { if (!estado.erro && estado.pagina?.temMais) solicitar(estado.pagina.proximaSequencia); } };
}
