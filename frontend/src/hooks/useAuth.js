import { useContext } from 'react';
import { AuthContext } from '../contexts/authContext';

export function useAuth() {
  const contexto = useContext(AuthContext);
  if (!contexto) throw new Error('useAuth deve ser usado dentro de AuthProvider.');
  return contexto;
}
