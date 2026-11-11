import { salvarSessao } from '../services/sessao';

export const SECRETARIA_TESTE = { id: 'u-secretaria', nome: 'Sofia Secretaria', perfil: 'SECRETARIA_ADMIN' };

/** Simula um login já feito, para testar telas que exigem sessão. */
export function entrarComo(usuario = SECRETARIA_TESTE) {
  salvarSessao('token-de-teste', usuario);
}
