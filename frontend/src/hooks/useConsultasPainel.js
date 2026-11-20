import { useEffect, useState } from 'react';
import { buscarAgendaDoDia, buscarProximasConsultas } from '../services/painelConsultasService';
import { useAuth } from './useAuth';

function useLeituraPainel(ler, parametros, habilitado = true) {
  const { usuario } = useAuth();
  const permitido = usuario?.perfil === 'MEDICO' || usuario?.perfil === 'SECRETARIA_ADMIN';
  const ativo = permitido && habilitado;
  const filtros = JSON.stringify(parametros);
  const [revisao, setRevisao] = useState(0);
  const [estado, setEstado] = useState({ chave: null, dados: null, erro: null });
  const chave = JSON.stringify([filtros, usuario?.id, usuario?.perfil, revisao]);
  const carregando = ativo && estado.chave !== chave;
  const atualizar = () => setRevisao((valor) => valor + 1);

  useEffect(() => {
    if (!ativo) return undefined;
    const controller = new AbortController();
    ler(JSON.parse(filtros), { signal: controller.signal })
      .then((dados) => { if (!controller.signal.aborted) setEstado({ chave, dados, erro: null }); })
      .catch((erro) => { if (!controller.signal.aborted) setEstado({ chave, dados: null, erro }); });
    return () => controller.abort();
  }, [ler, ativo, filtros, chave]);

  return { permitido, carregando, atualizar,
    dados: ativo && !carregando ? estado.dados : null,
    erro: ativo && !carregando ? estado.erro : null };
}

export function useAgendaDoDia(medicoId, dia) {
  return useLeituraPainel(buscarAgendaDoDia, { medicoId, dia }, Boolean(medicoId));
}

export function useProximasConsultas(medicoId) {
  return useLeituraPainel(buscarProximasConsultas, { medicoId });
}
