import { useEffect, useRef, useState } from 'react';
import { buscarHistoricoConsulta } from '../services/consultasService';

/** Estado independente do resumo: novas tentativas repetem só o GET da página que falhou. */
export function useHistoricoConsulta(consultaId) {
  const [pedido, setPedido] = useState({ aposSequencia: 0, revisao: 0 });
  const [estado, setEstado] = useState({ pedido: null, eventos: [], pagina: null, erro: null, primeiroNovoId: null });
  const eventosAtuais = useRef([]);
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
        setEstado({ pedido, eventos, pagina, erro: null, primeiroNovoId: pedido.aposSequencia > 0 ? pagina.eventos[0]?.id : null });
      })
      .catch((erro) => {
        if (controller.signal.aborted) return;
        const acessoRecusado = [400, 403, 404].includes(erro.status);
        if (acessoRecusado) eventosAtuais.current = [];
        setEstado((anterior) => ({ ...anterior, pedido, erro, primeiroNovoId: null,
          ...(acessoRecusado ? { eventos: [], pagina: null } : {}) }));
      })
      .finally(() => { if (!controller.signal.aborted) emLeitura.current = false; });
    return () => controller.abort();
  }, [consultaId, pedido]);

  function solicitar(aposSequencia) {
    if (emLeitura.current || carregando) return;
    emLeitura.current = true;
    setPedido((anterior) => ({ aposSequencia, revisao: anterior.revisao + 1 }));
  }

  return { ...estado, carregando, erro: carregando ? null : estado.erro,
    atualizar: () => solicitar(0), tentarNovamente: () => solicitar(pedido.aposSequencia),
    carregarMais: () => { if (!estado.erro && estado.pagina?.temMais) solicitar(estado.pagina.proximaSequencia); } };
}
