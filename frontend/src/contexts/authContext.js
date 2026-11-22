/*
Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

import { createContext } from 'react';

/** Valor: { usuario, sessaoExpirada, entrar(email, senha), sair() }. Fornecido por AuthProvider. */
export const AuthContext = createContext(null);
