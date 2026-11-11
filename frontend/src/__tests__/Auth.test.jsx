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

describe('sessão e rotas protegidas', () => {
  it('manda para o login quando ninguém entrou', async () => {
    const router = renderAt('/pacientes');

    expect(await screen.findByRole('heading', { name: 'Acesse sua conta' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    expect(screen.queryByRole('navigation', { name: 'Navegação principal' })).not.toBeInTheDocument();
  });

  it('depois do login volta para a página que pediu o acesso', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ token: 'abc', expiraEm: '2026-11-12T00:00:00Z', usuario: SECRETARIA_TESTE })));
    const user = userEvent.setup();
    const router = renderAt('/perfis');

    await user.type(await screen.findByLabelText(/E-mail/), 'secretaria@consultafacil.demo');
    await user.type(screen.getByLabelText(/Senha/), 'Demo@123');
    await user.click(screen.getByRole('button', { name: 'Entrar' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/perfis'));
    expect(screen.getByRole('button', { name: 'Conta de Sofia Secretaria' })).toBeInTheDocument();
    expect(sessionStorage.getItem(TOKEN_KEY)).toBe('abc');
  });

  it('mostra o usuário no cabeçalho no lugar de "Acessar conta" e permite sair', async () => {
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
    vi.stubGlobal('fetch', vi.fn(async () => json({ status: 401, erro: 'Sessão expirada.' }, 401)));
    const router = renderAt('/pacientes');

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(await screen.findByText('Sua sessão expirou. Entre novamente para continuar.')).toBeInTheDocument();
    expect(sessionStorage.getItem(TOKEN_KEY)).toBeNull();
  });
});

describe('menu e telas por perfil', () => {
  const menu = () => within(screen.getByRole('navigation', { name: 'Navegação principal' }))
    .getAllByRole('link').map((link) => link.textContent);

  it('secretaria vê todos os itens', () => {
    entrarComo();
    renderAt('/perfis');
    expect(menu()).toEqual(['Início', 'Pacientes', 'Médicos', 'Especialidades', 'Agenda', 'Perfis de acesso', 'Status do sistema']);
  });

  it('médico vê pacientes e agenda, sem cadastros-base nem status', () => {
    entrarComo({ id: 'u-medica', nome: 'Ana Martins', perfil: 'MEDICO' });
    renderAt('/perfis');
    expect(menu()).toEqual(['Início', 'Pacientes', 'Agenda', 'Perfis de acesso']);
  });

  it('paciente vê apenas início e perfis', () => {
    entrarComo({ id: 'u-paciente', nome: 'Paulo Paciente', perfil: 'PACIENTE' });
    renderAt('/perfis');
    expect(menu()).toEqual(['Início', 'Perfis de acesso']);
  });

  it('bloqueia a tela aberta pelo endereço quando o perfil não tem acesso', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    entrarComo({ id: 'u-medica', nome: 'Ana Martins', perfil: 'MEDICO' });
    renderAt('/medicos');
    expect(screen.getByRole('heading', { name: 'Acesso não permitido' })).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
