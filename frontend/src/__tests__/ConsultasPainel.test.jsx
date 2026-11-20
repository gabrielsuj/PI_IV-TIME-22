import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, MemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, expect, it, vi } from 'vitest';
import AgendaDiaPainel from '../components/painel/AgendaDiaPainel';
import ProximasConsultasPainel from '../components/painel/ProximasConsultasPainel';
import { AuthContext } from '../contexts/authContext';
import { routes } from '../routes';
import { entrarComo, SECRETARIA_TESTE } from './sessaoTeste';
import { indicadoresDaUrl } from './fixtures/indicadores';
import { agendaPainel, consultaDia, consultaProxima, MEDICO_PAINEL, OUTRO_MEDICO_PAINEL, proximasEmQuantidade } from './fixtures/consultasPainel';

const dia = '2026-11-23';
const MEDICO = { id: 'login-diferente-do-cadastro', nome: 'Ana Médica', perfil: 'MEDICO' };
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const medico = (id = MEDICO_PAINEL, nome = 'Ana Médica', ativo = true) => ({ id, nome, ativo });
const contexto = (children, usuario = SECRETARIA_TESTE) => <MemoryRouter><AuthContext.Provider value={{ usuario }}>{children}</AuthContext.Provider></MemoryRouter>;
const chamadas = (fetchMock, caminho) => fetchMock.mock.calls.filter(([url]) => new URL(url).pathname === caminho);

function mockApi({ calendario = [consultaDia()], proximas = [consultaProxima()], agenda = agendaPainel() } = {}) {
  const fetchMock = vi.fn(async (url) => {
    switch (new URL(url).pathname) {
      case '/medicos': return json([medico(), medico(OUTRO_MEDICO_PAINEL, 'Bruno Médico'), medico('inativo', 'Médico Inativo', false)]);
      case '/agendas': return agenda ? json(agenda) : json({ erro: 'Agenda não configurada' }, 404);
      case '/consultas': return json(calendario);
      case '/consultas/lista': return json(proximas);
      case '/indicadores': return json(indicadoresDaUrl(url));
      case '/health': return json({ status: 'ok' });
      default: return json({ erro: 'Não encontrado' }, 404);
    }
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

beforeEach(() => { sessionStorage.clear(); entrarComo(); });

it('secretaria seleciona um médico para a agenda e os horários locais não são deslocados', async () => {
  const fetchMock = mockApi();
  const user = userEvent.setup();
  render(contexto(<AgendaDiaPainel dia={dia} />));
  expect(await screen.findByRole('option', { name: 'Ana Médica' })).toBeInTheDocument();
  expect(screen.queryByRole('option', { name: 'Médico Inativo' })).not.toBeInTheDocument();
  expect(chamadas(fetchMock, '/consultas')).toHaveLength(0);
  await user.selectOptions(screen.getByLabelText('Médico da agenda do dia'), MEDICO_PAINEL);
  expect(await screen.findByText('Paula da Agenda')).toBeInTheDocument();
  expect(screen.getByText('09:00 às 09:30')).toBeInTheDocument();
  expect(screen.getByText('Fuso da agenda: America/Sao_Paulo.')).toBeInTheDocument();
  const url = new URL(chamadas(fetchMock, '/consultas')[0][0]);
  expect(Object.fromEntries(url.searchParams)).toEqual({ medicoId: MEDICO_PAINEL, de: dia, ate: dia });
  expect(chamadas(fetchMock, '/consultas/lista')).toHaveLength(0);
});

it('médico usa meu na agenda sem carregar catálogo nem enviar o ID de login', async () => {
  const fetchMock = mockApi();
  render(contexto(<AgendaDiaPainel dia={dia} />, MEDICO));
  expect(await screen.findByText('Paula da Agenda')).toBeInTheDocument();
  expect(screen.queryByLabelText('Médico da agenda do dia')).not.toBeInTheDocument();
  expect(chamadas(fetchMock, '/medicos')).toHaveLength(0);
  for (const [url] of fetchMock.mock.calls) expect(new URL(url).searchParams.get('medicoId')).toBe('meu');
});

it('exibe todas as consultas do dia, incluindo cancelamentos e atendimentos passados', async () => {
  mockApi({ calendario: ['AGENDADA', 'CONFIRMADA', 'CANCELADA', 'CONCLUIDA', 'FALTA'].map((status) => consultaDia({ id: status, status })) });
  render(contexto(<AgendaDiaPainel dia={dia} />, MEDICO));
  expect(await screen.findByText('Cancelada')).toBeInTheDocument();
  expect(screen.getByText('Concluída')).toBeInTheDocument();
  expect(within(screen.getByRole('table', { name: 'Consultas na agenda do dia' })).getAllByRole('row')).toHaveLength(6);
});

it('exibe a data nos horários que atravessam o limite do dia', async () => {
  mockApi({ calendario: [consultaDia({ inicio: '2026-11-22T23:45', fim: '2026-11-23T00:15' })] });
  render(contexto(<AgendaDiaPainel dia={dia} />, MEDICO));
  expect(await screen.findByText('23:45 (22/11/2026) às 00:15')).toBeInTheDocument();
});

it('agenda ausente avisa e preserva a consulta existente', async () => {
  mockApi({ agenda: null });
  render(contexto(<AgendaDiaPainel dia={dia} />, MEDICO));
  expect(await screen.findByText(/Este médico ainda não tem agenda configurada/)).toBeInTheDocument();
  expect(screen.getByText('Paula da Agenda')).toBeInTheDocument();
});

it('vazio real da agenda é diferente de falha', async () => {
  mockApi({ calendario: [] });
  render(contexto(<AgendaDiaPainel dia={dia} />, MEDICO));
  expect(await screen.findByText('Nenhuma consulta na agenda deste dia.')).toBeInTheDocument();
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});

it('próximas exibe cinco itens ordenados, com nomes e horário no fuso da clínica', async () => {
  const fetchMock = mockApi({ proximas: proximasEmQuantidade(7).reverse() });
  render(contexto(<ProximasConsultasPainel />));
  expect(await screen.findByText('Paciente 0')).toBeInTheDocument();
  const table = within(screen.getByRole('table', { name: 'Resumo das próximas consultas' }));
  expect(table.getAllByRole('row')).toHaveLength(6);
  expect(table.getByText('09:00 às 09:30')).toBeInTheDocument();
  expect(screen.queryByText('Paciente 5')).not.toBeInTheDocument();
  expect(screen.getByText('Mostrando 5 de 7 consultas recebidas.')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Abrir lista de consultas' })).toHaveAttribute('href', '/consultas');
  expect(chamadas(fetchMock, '/consultas/lista')).toHaveLength(1);
  expect(chamadas(fetchMock, '/indicadores')).toHaveLength(0);
});

it('avisa o limite de cem sem chamar os cem de total completo', async () => {
  mockApi({ proximas: proximasEmQuantidade(100) });
  render(contexto(<ProximasConsultasPainel />));
  expect(await screen.findByText(/A listagem está limitada às primeiras 100 consultas/)).toBeInTheDocument();
  expect(screen.getByText('Mostrando 5 de 100 consultas recebidas.')).toBeInTheDocument();
});

it('próximas vazias e falha não usam a mesma mensagem', async () => {
  mockApi({ proximas: [] });
  const view = render(contexto(<ProximasConsultasPainel />));
  expect(await screen.findByText('Nenhuma próxima consulta encontrada.')).toBeInTheDocument();
  view.unmount();
  vi.stubGlobal('fetch', vi.fn(async () => json({ erro: 'Leitura indisponível.' }, 503)));
  render(contexto(<ProximasConsultasPainel />));
  expect(await screen.findByRole('alert')).toHaveTextContent('Leitura indisponível.');
  expect(screen.queryByText('Nenhuma próxima consulta encontrada.')).not.toBeInTheDocument();
});

it('nova tentativa repete somente a seção de próximas consultas que falhou', async () => {
  const fetchMock = vi.fn().mockResolvedValueOnce(json({ erro: 'Tente novamente.' }, 503)).mockResolvedValueOnce(json([consultaProxima()]));
  vi.stubGlobal('fetch', fetchMock);
  const user = userEvent.setup();
  render(contexto(<ProximasConsultasPainel />));
  await screen.findByRole('alert');
  await user.click(screen.getByRole('button', { name: 'Tentar carregar próximas consultas novamente' }));
  expect(await screen.findByText('Bruno da Lista')).toBeInTheDocument();
  expect(fetchMock).toHaveBeenCalledTimes(2);
  expect(fetchMock.mock.calls.every(([url, options]) => new URL(url).pathname === '/consultas/lista' && options.method === 'GET')).toBe(true);
});

it('resposta antiga não substitui as próximas do novo filtro', async () => {
  let resolverAntiga;
  const fetchMock = vi.fn().mockImplementationOnce(() => new Promise((resolve) => { resolverAntiga = resolve; }))
    .mockResolvedValueOnce(json([consultaProxima({ medicoId: OUTRO_MEDICO_PAINEL, pacienteNome: 'Paciente Atual' })]));
  vi.stubGlobal('fetch', fetchMock);
  const view = render(contexto(<ProximasConsultasPainel medicoId={MEDICO_PAINEL} />));
  view.rerender(contexto(<ProximasConsultasPainel medicoId={OUTRO_MEDICO_PAINEL} />));
  expect(await screen.findByText('Paciente Atual')).toBeInTheDocument();
  await act(async () => { resolverAntiga(json([consultaProxima()])); });
  expect(screen.queryByText('Bruno da Lista')).not.toBeInTheDocument();
  expect(screen.getByText('Paciente Atual')).toBeInTheDocument();
  expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true);
});

it('paciente não monta nem consulta as seções operacionais', () => {
  const fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  render(contexto(<><AgendaDiaPainel dia={dia} /><ProximasConsultasPainel /></>, { id: 'paciente', perfil: 'PACIENTE' }));
  expect(screen.queryByText('Agenda do dia')).not.toBeInTheDocument();
  expect(screen.queryByText('Próximas consultas')).not.toBeInTheDocument();
  expect(fetchMock).not.toHaveBeenCalled();
});

it('falha dos indicadores na entrada não esconde agenda, próximas ou navegação', async () => {
  const api = mockApi();
  const implementation = api.getMockImplementation();
  api.mockImplementation((url, options) => new URL(url).pathname === '/indicadores'
    ? Promise.resolve(json({ erro: 'Indicadores indisponíveis.' }, 503)) : implementation(url, options));
  entrarComo(MEDICO);
  render(<RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/'] })} />);
  expect(await screen.findByText('Indicadores indisponíveis.')).toBeInTheDocument();
  expect(await screen.findByText('Paula da Agenda')).toBeInTheDocument();
  expect(await screen.findByText('Bruno da Lista')).toBeInTheDocument();
  expect(screen.getByRole('navigation', { name: 'Navegação principal' })).toBeInTheDocument();
  expect(chamadas(api, '/medicos')).toHaveLength(0);
});

it('não deriva os indicadores da listagem limitada de próximas', async () => {
  mockApi({ proximas: proximasEmQuantidade(100) });
  render(<RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/'] })} />);
  expect(await screen.findByText('75,00%')).toBeInTheDocument();
  expect(await screen.findByText('Mostrando 5 de 100 consultas recebidas.')).toBeInTheDocument();
  expect(screen.getByText('6 de 8 vagas ocupadas')).toBeInTheDocument();
});

it('desmontagem interrompe a leitura das próximas', async () => {
  const fetchMock = vi.fn(() => new Promise(() => {}));
  vi.stubGlobal('fetch', fetchMock);
  const view = render(contexto(<ProximasConsultasPainel />));
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
  view.unmount();
  expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true);
});
