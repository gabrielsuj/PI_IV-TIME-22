import { useCallback, useEffect, useState } from 'react';
import { buscarIndicadores } from '../services/indicadoresService';
import { useAuth } from './useAuth';

export function useIndicadores({ de, ate, medicoId }) {
  const { usuario } = useAuth();
  const permitido = usuario?.perfil === 'MEDICO' || usuario?.perfil === 'SECRETARIA_ADMIN';
  const [revisao, setRevisao] = useState(0);
  const [estado, setEstado] = useState({ chave: null, dados: null, erro: null });
  const chave = JSON.stringify([de, ate, medicoId ?? null, usuario?.id, usuario?.perfil, revisao]);
  const carregando = permitido && estado.chave !== chave;
  const atualizar = useCallback(() => setRevisao((valor) => valor + 1), []);

  useEffect(() => {
    if (!permitido) return undefined;
    const controller = new AbortController();
    buscarIndicadores({ de, ate, medicoId }, { signal: controller.signal })
      .then((dados) => {
        if (!controller.signal.aborted) setEstado({ chave, dados, erro: null });
      })
      .catch((erro) => {
        if (!controller.signal.aborted) setEstado({ chave, dados: null, erro });
      });
    return () => controller.abort();
  }, [de, ate, medicoId, permitido, chave]);

  return { permitido, carregando, atualizar,
    dados: permitido && !carregando ? estado.dados : null,
    erro: permitido && !carregando ? estado.erro : null };
}
