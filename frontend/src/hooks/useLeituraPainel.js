import { useEffect, useRef, useState } from 'react';
import { useAuth } from './useAuth';

export function useLeituraPainel(ler, parametros, habilitado = true) {
  const { usuario } = useAuth();
  const permitido = usuario?.perfil === 'MEDICO' || usuario?.perfil === 'SECRETARIA_ADMIN';
  const ativo = permitido && habilitado;
  const filtros = JSON.stringify(parametros);
  const [revisao, setRevisao] = useState(0);
  const [estado, setEstado] = useState({ chave: null, dados: null, erro: null });
  const emLeitura = useRef(false);
  const chave = JSON.stringify([filtros, usuario?.id, usuario?.perfil, revisao]);
  const carregando = ativo && estado.chave !== chave;

  useEffect(() => {
    if (!ativo) return undefined;
    const controller = new AbortController();
    emLeitura.current = true;
    async function carregar() {
      try {
        const dados = await ler(JSON.parse(filtros), { signal: controller.signal });
        if (!controller.signal.aborted) setEstado({ chave, dados, erro: null });
      } catch (erro) {
        if (!controller.signal.aborted) setEstado({ chave, dados: null, erro });
      } finally {
        if (!controller.signal.aborted) emLeitura.current = false;
      }
    }
    carregar();
    return () => controller.abort();
  }, [ler, ativo, filtros, chave]);

  const atualizar = () => {
    if (!ativo || carregando || emLeitura.current) return;
    emLeitura.current = true;
    setRevisao((valor) => valor + 1);
  };

  return { permitido, carregando, carregamentoInicial: carregando && revisao === 0, atualizar,
    dados: ativo && !carregando ? estado.dados : null,
    erro: ativo && !carregando ? estado.erro : null };
}
