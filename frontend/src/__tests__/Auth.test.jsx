import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { routes } from '../routes';
import { TOKEN_KEY } from '../services/sessao';
import { entrarComo, SECRETARIA_TESTE } from './sessaoTeste';

function renderAt(path) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(<RouterProvider router={router} />);
  return router;
}

const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

beforeEach(() => {
  sessionStorage.clear();
});

describe('sessÃ£o e rotas protegidas', () => {
  it('manda para o login quando ninguÃ©m entrou', async () => {
    const router = renderAt('/pacientes');

    expect(await screen.findByRole('heading', { name: 'Acesse sua conta' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    expect(screen.queryByRole('navigation', { name: 'NavegaÃ§Ã£o principal' })).not.toBeInTheDocument();
  });

  it('depois do login volta para a pÃ¡gina que pediu o acesso', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ token: 'abc', expiraEm: '2026-11-12T00:00:00Z', usuario: SECRETARIA_TESTE })));
    const user = userEvent.setup();
    const router = renderAt('/perfis');

    await user.type(await screen.findByLabelText(/E-mail/), 'secretaria@consultafacil.demo');
    await user.type(screen.getByLabelText(/Senha/), 'Demo@123');
    await user.click(screen.getByRole('button', { name: 'Entrar' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/perfis'));
    expect(await screen.findByRole('button', { name: 'Conta de Sofia Secretaria' })).toBeInTheDocument();
    expect(sessionStorage.getItem(TOKEN_KEY)).toBe('abc');
  });

  it('mostra o usuÃ¡rio no cabeÃ§alho no lugar de "Acessar conta" e permite sair', async () => {
    entrarComo();
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    const router = renderAt('/perfis');

    expect(screen.queryByRole('link', { name: 'Acessar conta' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Conta de Sofia Secretaria' }));
    expect(screen.getByText('Secretaria / Administrador', { selector: '.user-menu__who span' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Sair' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(fetchMock).toHaveBeenCalledWith('http://api.teste/auth/logout', expect.objectContaining({ method: 'POST' }));
    expect(sessionStorage.getItem(TOKEN_KEY)).toBeNull();
  });

  it('volta para o login quando a API recusa o token', async () => {
    entrarComo();
    vi.stubGlobal('fetch', vi.fn(async () => json({ status: 401, erro: 'SessÃ£o expirada.' }, 401)));
    const router = renderAt('/pacientes');

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(await screen.findByText('Sua sessÃ£o expirou. Entre novamente para continuar.')).toBeInTheDocument();
    expect(sessionStorage.getItem(TOKEN_KEY)).toBeNull();
  });
});

describe('menu e telas por perfil', () => {
  const menu = () => within(screen.getByRole('navigation', { name: 'NavegaÃ§Ã£o principal' }))
    .getAllByRole('link').map((link) => link.textContent);

  it('secretaria vÃª todos os itens', () => {
    entrarComo();
    renderAt('/perfis');
    expect(menu()).toEqual(['InÃ­cio', 'Nova consulta', 'Consultas', 'Fila de espera', 'Pacientes', 'MÃ©dicos', 'Especialidades', 'Agenda', 'CalendÃ¡rio', 'Perfis de acesso', 'Status do sistema']);
  });

  it('mÃ©dico vÃª pacientes e agenda, sem cadastros-base nem status', () => {
    entrarComo({ id: 'u-medica', nome: 'Ana Martins', perfil: 'MEDICO' });
    renderAt('/perfis');
    expect(menu()).toEqual(['InÃ­cio', 'Consultas', 'Pacientes', 'Agenda', 'CalendÃ¡rio', 'Perfis de acesso']);
  });

  it('paciente vÃª inÃ­cio, nova consulta e perfis', () => {
    entrarComo({ id: 'u-paciente', nome: 'Paulo Paciente', perfil: 'PACIENTE' });
    renderAt('/perfis');
    expect(menu()).toEqual(['InÃ­cio', 'Nova consulta', 'Consultas', 'Fila de espera', 'Perfis de acesso']);
  });

  it('bloqueia a tela aberta pelo endereÃ§o quando o perfil nÃ£o tem acesso', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    entrarComo({ id: 'u-medica', nome: 'Ana Martins', perfil: 'MEDICO' });
    renderAt('/medicos');
    expect(screen.getByRole('heading', { name: 'Acesso nÃ£o permitido' })).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
