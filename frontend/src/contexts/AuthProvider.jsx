import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '../services/api';
import { lerUsuario, limparSessao, salvarSessao, SESSAO_EXPIRADA } from '../services/sessao';
import { AuthContext } from './authContext';
import { encerrarRascunhos, iniciarRascunhos, suspenderRascunhos } from '../services/rascunhoPacientes';

export default function AuthProvider({ children }) {
  const [usuario, setUsuario] = useState(lerUsuario);
  const [sessaoExpirada, setSessaoExpirada] = useState(false);

  useEffect(() => {
    if (usuario) iniciarRascunhos(usuario.id);
  }, [usuario]);

  useEffect(() => {
    const aoExpirar = () => {
      suspenderRascunhos();
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
    setSessaoExpirada(false);
    setUsuario(data.usuario);
    return data.usuario;
  }, []);

  const sair = useCallback(async () => {
    encerrarRascunhos();
    try {
      await api.post('/auth/logout');
    } catch {
      // A sessão local é encerrada mesmo que a API não responda.
    }
    limparSessao();
    encerrarRascunhos();
    setUsuario(null);
  }, []);

  const value = useMemo(() => ({ usuario, sessaoExpirada, entrar, sair }), [usuario, sessaoExpirada, entrar, sair]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
