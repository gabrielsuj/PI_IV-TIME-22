/**
 * Sessão do usuário no navegador.
 *
 * O token e os dados básicos do usuário ficam no sessionStorage (somem ao
 * fechar a aba). Quem decide se o token ainda vale é a API: quando ela
 * responde 401, o cliente HTTP chama encerrarSessaoExpirada() e a interface
 * volta para a tela de login.
 */
export const TOKEN_KEY = 'consultafacil.accessToken';
export const USUARIO_KEY = 'consultafacil.usuario';
export const SESSAO_EXPIRADA = 'consultafacil:sessao-expirada';

/** Usuário da sessão atual ({ id, nome, perfil }) ou null quando ninguém entrou. */
export function lerUsuario() {
  if (!sessionStorage.getItem(TOKEN_KEY)) return null;
  try {
    return JSON.parse(sessionStorage.getItem(USUARIO_KEY)) ?? null;
  } catch {
    return null;
  }
}

export function salvarSessao(token, usuario) {
  sessionStorage.setItem(TOKEN_KEY, token);
  sessionStorage.setItem(USUARIO_KEY, JSON.stringify(usuario));
}

export function limparSessao() {
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(USUARIO_KEY);
}

/** Chamado quando a API recusa o token (401): limpa a sessão e avisa a interface. */
export function encerrarSessaoExpirada() {
  limparSessao();
  window.dispatchEvent(new Event(SESSAO_EXPIRADA));
}
