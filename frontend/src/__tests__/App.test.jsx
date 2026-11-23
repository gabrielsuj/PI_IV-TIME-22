/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { routes } from '../routes';
import { entrarComo } from './sessaoTeste';
import { indicadoresDaUrl } from './fixtures/indicadores';
import { agendaPainel } from './fixtures/consultasPainel';

beforeEach(() => {
  sessionStorage.clear();
  entrarComo();
});

function renderAt(path) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(<RouterProvider router={router} />);
  return router;
}

function mockHealth(implementation) {
  const fetchMock = vi.fn((url, options) => {
    const caminho = new URL(url).pathname;
    let dados;
    if (caminho === '/indicadores') dados = indicadoresDaUrl(url);
    else if (caminho === '/agendas') dados = agendaPainel();
    else if (['/medicos', '/consultas', '/consultas/lista'].includes(caminho)) dados = [];
    else return implementation(url, options);
    return Promise.resolve(new Response(JSON.stringify(dados), { headers: { 'Content-Type': 'application/json' } }));
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

const healthy = async () =>
  new Response(JSON.stringify({ status: 'ok' }), {
    headers: { 'Content-Type': 'application/json' },
  });

// O roteador em memória permite abrir endereços diretamente e conferir a navegação sem
// depender do histórico do navegador.
describe('roteamento', () => {
  it.each(['MEDICO', 'PACIENTE'])('os atalhos iniciais respeitam o perfil %s', async (perfil) => {
    entrarComo({ id: 'u-perfil', nome: 'Teste', perfil });
    mockHealth(healthy);
    renderAt('/');
    expect(await screen.findByText('API disponível')).toBeInTheDocument();
    const main = within(screen.getByRole('main'));
    expect(main.queryByRole('link', { name: 'Gerenciar médicos' })).not.toBeInTheDocument();
    expect(main.queryByRole('link', { name: 'Gerenciar especialidades' })).not.toBeInTheDocument();
    expect(main.queryByRole('link', { name: 'Ver detalhes da conexão' })).not.toBeInTheDocument();
    if (perfil === 'MEDICO') expect(main.getByRole('link', { name: 'Consultar pacientes' })).toHaveAttribute('href', '/pacientes');
    else expect(main.queryByRole('link', { name: /pacientes/i })).not.toBeInTheDocument();
  });

  it('renderiza a página inicial com o cabeçalho de navegação', async () => {
    mockHealth(healthy);
    renderAt('/');

    expect(screen.getByRole('heading', { level: 1, name: 'Painel operacional' })).toBeInTheDocument();
    const nav = screen.getByRole('navigation', { name: 'Navegação principal' });
    expect(within(nav).getByRole('link', { name: 'Início' })).toHaveClass('active');
    expect(await screen.findByText('API disponível')).toBeInTheDocument();
  });

  it.each(['SECRETARIA_ADMIN', 'MEDICO', 'PACIENTE'])('a entrada inicial e os acessos respeitam o perfil %s', async (perfil) => {
    entrarComo({ id: 'u-inicio', nome: 'Teste', perfil });
    const fetchMock = mockHealth(healthy);
    renderAt('/');
    expect(await screen.findByText('API disponível')).toBeInTheDocument();
    const main = within(screen.getByRole('main'));
    const paciente = perfil === 'PACIENTE';
    expect(main.getByRole('heading', { level: 1, name: paciente ? 'Minha área' : 'Painel operacional' })).toBeInTheDocument();
    if (paciente) {
      expect(main.getByRole('link', { name: 'Ver minhas consultas' })).toHaveAttribute('href', '/consultas');
      expect(main.getByRole('link', { name: 'Ver avisos de vaga' })).toHaveAttribute('href', '/avisos-vaga');
      expect(main.getByRole('link', { name: 'Ver notificações' })).toHaveAttribute('href', '/notificacoes');
      expect(main.queryByRole('link', { name: 'Abrir calendário' })).not.toBeInTheDocument();
      expect(main.queryByRole('heading', { name: /indicadores|painel operacional/i })).not.toBeInTheDocument();
    } else {
      const calendario = new URL(main.getByRole('link', { name: 'Abrir calendário' }).getAttribute('href'), 'http://app.teste');
      expect(calendario.pathname).toBe('/calendario');
      expect(calendario.searchParams.get('visao')).toBe('dia');
      expect(main.getByRole('link', { name: 'Ver consultas' })).toHaveAttribute('href', '/consultas');
      expect(main.queryByRole('link', { name: 'Ver avisos de vaga' })).not.toBeInTheDocument();
    }
    if (perfil === 'MEDICO') expect(main.queryByRole('link', { name: 'Nova consulta' })).not.toBeInTheDocument();
    else expect(main.getByRole('link', { name: 'Nova consulta' })).toHaveAttribute('href', '/nova-consulta');
    if (paciente) expect(fetchMock.mock.calls.every(([url]) => url === 'http://api.teste/health')).toBe(true);
    else expect(fetchMock.mock.calls.some(([url]) => new URL(url).pathname === '/indicadores')).toBe(true);
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
    expect(screen.getByText(/Não verifica o MongoDB nem o serviço de CPF/)).toBeInTheDocument();
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
    expect(fetchMock.mock.calls.filter(([url]) => url === 'http://api.teste/health')).toHaveLength(2);
  });
});
