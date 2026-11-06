import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { describe, expect, it, vi } from 'vitest';
import { routes } from '../routes';

function renderAt(path) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(<RouterProvider router={router} />);
  return router;
}

function mockHealth(implementation) {
  const fetchMock = vi.fn(implementation);
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

const healthy = async () =>
  new Response(JSON.stringify({ status: 'ok' }), {
    headers: { 'Content-Type': 'application/json' },
  });

describe('roteamento', () => {
  it('renderiza a página inicial com o cabeçalho de navegação', async () => {
    mockHealth(healthy);
    renderAt('/');

    expect(screen.getByRole('heading', { level: 1, name: 'ConsultaFácil' })).toBeInTheDocument();
    const nav = screen.getByRole('navigation', { name: 'Navegação principal' });
    expect(within(nav).getByRole('link', { name: 'Início' })).toHaveClass('active');
    expect(await screen.findByText('API disponível')).toBeInTheDocument();
  });

  it('navega para a página de status pelo menu', async () => {
    mockHealth(healthy);
    const user = userEvent.setup();
    const router = renderAt('/');

    const nav = screen.getByRole('navigation', { name: 'Navegação principal' });
    await user.click(within(nav).getByRole('link', { name: 'Status do sistema' }));

    expect(router.state.location.pathname).toBe('/status');
    expect(screen.getByRole('heading', { level: 1, name: 'Status do sistema' })).toBeInTheDocument();
  });

  it('exibe a página de não encontrado para rotas desconhecidas', () => {
    renderAt('/rota-inexistente');

    expect(screen.getByRole('heading', { name: 'Página não encontrada' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Voltar ao início' })).toHaveAttribute('href', '/');
  });
});

describe('verificação do /health', () => {
  it('mostra os detalhes da resposta na página de status', async () => {
    const fetchMock = mockHealth(healthy);
    renderAt('/status');

    expect(screen.getByRole('status')).toHaveTextContent('Verificando…');
    expect(await screen.findByText('API disponível')).toBeInTheDocument();
    expect(screen.getByText('http://api.teste/health')).toBeInTheDocument();
    expect(screen.getByLabelText('Resposta da API')).toHaveTextContent('"status": "ok"');
    expect(fetchMock).toHaveBeenCalledWith('http://api.teste/health', expect.any(Object));
  });

  it('informa quando a API está fora do ar', async () => {
    mockHealth(async () => {
      throw new TypeError('Failed to fetch');
    });
    renderAt('/status');

    expect(await screen.findByText('API indisponível')).toBeInTheDocument();
    expect(screen.getByText(/Não foi possível conectar à API/)).toBeInTheDocument();
  });

  it('exibe o código HTTP quando a API responde com erro', async () => {
    mockHealth(async () => new Response(null, { status: 503 }));
    renderAt('/status');

    expect(await screen.findByText('API indisponível')).toBeInTheDocument();
    expect(screen.getByText('503')).toBeInTheDocument();
  });

  it('refaz a verificação ao clicar em "Verificar novamente"', async () => {
    const fetchMock = mockHealth(async () => {
      throw new TypeError('Failed to fetch');
    });
    const user = userEvent.setup();
    renderAt('/');
    expect(await screen.findByText('API indisponível')).toBeInTheDocument();

    fetchMock.mockImplementation(healthy);
    await user.click(screen.getByRole('button', { name: 'Verificar novamente' }));

    expect(await screen.findByText('API disponível')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
