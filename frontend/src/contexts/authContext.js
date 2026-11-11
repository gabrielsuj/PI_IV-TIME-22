import { createContext } from 'react';

/** Valor: { usuario, sessaoExpirada, entrar(email, senha), sair() }. Fornecido por AuthProvider. */
export const AuthContext = createContext(null);
