/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '../services/api';
import { lerUsuario, limparSessao, salvarSessao, SESSAO_EXPIRADA } from '../services/sessao';
import { AuthContext } from './authContext';
import { encerrarRascunhos, iniciarRascunhos, suspenderRascunhos } from '../services/rascunhoPacientes';
import { encerrarRascunhoAgendamento, iniciarRascunhoAgendamento, suspenderRascunhoAgendamento } from '../services/rascunhoAgendamento';

export default function AuthProvider({ children }) {
  const [usuario, setUsuario] = useState(lerUsuario);
  const [sessaoExpirada, setSessaoExpirada] = useState(false);

  useEffect(() => {
    if (usuario) {
      // Ao reconhecer a conta, os rascunhos retomam seu contexto; dados de outro
      // usuário são descartados pelos serviços de memória.
      iniciarRascunhos(usuario.id);
      iniciarRascunhoAgendamento(usuario);
    }
  }, [usuario]);

  useEffect(() => {
    const aoExpirar = () => {
      suspenderRascunhos();
      suspenderRascunhoAgendamento();
      setUsuario(null);
      setSessaoExpirada(true);
    };
    window.addEventListener(SESSAO_EXPIRADA, aoExpirar);
    return () => window.removeEventListener(SESSAO_EXPIRADA, aoExpirar);
  }, []);

  const entrar = useCallback(async (email, senha) => {
    const data = await api.post('/auth/login', { email, senha });
    if (typeof data?.token !== 'string' || !data.token.trim() || !data.usuario) {
      throw new Error('Não foi possível concluir o acesso. Tente novamente.');
    }
    salvarSessao(data.token, data.usuario);
    iniciarRascunhos(data.usuario.id);
    iniciarRascunhoAgendamento(data.usuario);
    setSessaoExpirada(false);
    setUsuario(data.usuario);
    return data.usuario;
  }, []);

  const sair = useCallback(async () => {
    encerrarRascunhos();
    encerrarRascunhoAgendamento();
    try {
      await api.post('/auth/logout');
    } catch {
      // A sessão local é encerrada mesmo que a API não responda.
    }
    limparSessao();
    encerrarRascunhos();
    encerrarRascunhoAgendamento();
    setUsuario(null);
  }, []);

  const value = useMemo(() => ({ usuario, sessaoExpirada, entrar, sair }), [usuario, sessaoExpirada, entrar, sair]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
