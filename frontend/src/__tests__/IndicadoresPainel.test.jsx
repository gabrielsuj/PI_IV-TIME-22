/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { StrictMode } from 'react';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import IndicadoresPainel from '../components/painel/IndicadoresPainel';
import { AuthContext } from '../contexts/authContext';
import { routes } from '../routes';
import { entrarComo, SECRETARIA_TESTE } from './sessaoTeste';
import { indicadores, indicadoresDaUrl } from './fixtures/indicadores';

const periodo = { de: '2026-11-23', ate: '2026-11-23' };
const medicoId = '000000000000000000000001';
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const contexto = (props = periodo, usuario = SECRETARIA_TESTE) => (
  <AuthContext.Provider value={{ usuario }}><IndicadoresPainel {...props} /></AuthContext.Provider>
);

beforeEach(() => {
  sessionStorage.clear();
  entrarComo();
});
afterEach(() => vi.useRealTimers());

// Os valores recebidos são comparados à apresentação para conferir que a tela formata
// os percentuais sem refazer o cálculo.
it('exibe os cinco percentuais e as contagens da API', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => json(indicadores())));
  render(contexto());
  expect(await screen.findByText('75,00%')).toBeInTheDocument();
  for (const [nome, valor, apoio] of [
    ['Ocupação', '75,00%', '6 de 8 vagas ocupadas'],
    ['Faltas', '33,33%', 'Faltas: 1 · Consultas com desfecho: 3'],
    ['Cancelamentos', '16,67%', 'Canceladas: 1 · Total de consultas: 6'],
    ['Confirmações', '66,67%', 'Com confirmação registrada: 4 · Total de consultas: 6'],
    ['Vagas preenchidas pela fila', '40,00%', 'Pela fila: 2 · Consultas não canceladas: 5'],
  ]) {
    const card = within(screen.getByRole('region', { name: nome }));
    expect(card.getByText(valor)).toBeInTheDocument();
    expect(card.getByText(apoio)).toBeInTheDocument();
  }
  expect(screen.getByText(/Consultas iniciadas de 23\/11\/2026 · America\/Sao_Paulo/)).toBeInTheDocument();
  expect(screen.getByText(/Capacidade calculada pela agenda atual/)).toBeInTheDocument();
});

it('formata o percentual recebido sem recalcular a taxa pelas contagens', async () => {
  const dados = indicadores();
  dados.ocupacao.percentual = 12.34;
  vi.stubGlobal('fetch', vi.fn(async () => json(dados)));
  render(contexto());
  expect(await screen.findByText('12,34%')).toBeInTheDocument();
  expect(screen.queryByText('75,00%')).not.toBeInTheDocument();
});

it('distingue zero verdadeiro de uma métrica sem base', async () => {
  const dados = indicadores();
  dados.ocupacao = { numerador: 0, denominador: 0, percentual: null, situacao: 'SEM_BASE' };
  dados.faltas = { numerador: 0, denominador: 3, percentual: 0, situacao: 'DISPONIVEL' };
  vi.stubGlobal('fetch', vi.fn(async () => json(dados)));
  render(contexto());
  expect(await screen.findByText('0,00%')).toBeInTheDocument();
  const ocupacao = within(screen.getByRole('region', { name: 'Ocupação' }));
  expect(ocupacao.getByText('—')).toBeInTheDocument();
  expect(ocupacao.getByText('Sem base para cálculo')).toBeInTheDocument();
  expect(ocupacao.queryByText('0,00%')).not.toBeInTheDocument();
});

it('mostra pendências e fontes incompletas sem inventar percentuais', async () => {
  const dados = indicadores();
  dados.confirmacao = { ...dados.confirmacao, percentual: null, situacao: 'DADOS_PENDENTES' };
  dados.ocupacao = { ...dados.ocupacao, percentual: null, situacao: 'DADOS_INCOMPLETOS' };
  dados.preenchimentoFila = { ...dados.preenchimentoFila, percentual: null, situacao: 'DADOS_INCOMPLETOS' };
  Object.assign(dados.qualidade, { consultasComProjecaoPendente: 1, consultasSemBaseHistorica: 2, medicosSemAgenda: 1, inscricoesComVinculoInvalido: 1 });
  vi.stubGlobal('fetch', vi.fn(async () => json(dados)));
  render(contexto());
  expect(await screen.findByText('Atualização pendente')).toBeInTheDocument();
  expect(screen.getAllByText('Dados incompletos')).toHaveLength(2);
  expect(screen.getAllByText('—')).toHaveLength(3);
  expect(screen.getByText('Consultas com histórico em atualização: 1.')).toBeInTheDocument();
  expect(screen.getByText('Consultas sem histórico suficiente: 2.')).toBeInTheDocument();
  expect(screen.getByText('Profissionais sem agenda configurada: 1.')).toBeInTheDocument();
  expect(screen.getByText('Inscrições com vínculo de consulta inválido: 1.')).toBeInTheDocument();
});

it('informa falha e tenta novamente somente o GET de indicadores', async () => {
  const fetchMock = vi.fn().mockResolvedValueOnce(json({ erro: 'Os dados mudaram. Tente novamente.' }, 503))
    .mockResolvedValueOnce(json(indicadores()));
  vi.stubGlobal('fetch', fetchMock);
  const user = userEvent.setup();
  render(contexto());
  expect(await screen.findByRole('alert')).toHaveTextContent('Os dados mudaram. Tente novamente.');
  expect(screen.queryByText('75,00%')).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));
  expect(await screen.findByText('75,00%')).toBeInTheDocument();
  expect(fetchMock).toHaveBeenCalledTimes(2);
  expect(fetchMock.mock.calls.every(([url, options]) => new URL(url).pathname === '/indicadores' && options.method === 'GET')).toBe(true);
});

it('não transforma resposta inválida em um painel de zeros', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => json({})));
  render(contexto());
  expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível ler os indicadores recebidos.');
  expect(screen.queryByText('0,00%')).not.toBeInTheDocument();
});

it('não consulta nem apresenta indicadores para paciente mesmo em montagem direta', () => {
  const fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  render(contexto(periodo, { id: 'paciente-login', perfil: 'PACIENTE' }));
  expect(screen.queryByRole('region', { name: 'Indicadores' })).not.toBeInTheDocument();
  expect(fetchMock).not.toHaveBeenCalled();
});

it('deixa o servidor resolver o cadastro do médico sem usar o ID do login como filtro', async () => {
  const fetchMock = vi.fn(async () => json(indicadores({ medicoId })));
  vi.stubGlobal('fetch', fetchMock);
  render(contexto(periodo, { id: 'login-diferente-do-cadastro', perfil: 'MEDICO' }));
  expect(await screen.findByText('75,00%')).toBeInTheDocument();
  expect(new URL(fetchMock.mock.calls[0][0]).searchParams.has('medicoId')).toBe(false);
});

it('descarta resposta antiga depois da troca do período', async () => {
  let resolverAntiga;
  const fetchMock = vi.fn().mockImplementationOnce(() => new Promise((resolve) => { resolverAntiga = resolve; }))
    .mockResolvedValueOnce(json(indicadores({ de: '2026-11-24' })));
  vi.stubGlobal('fetch', fetchMock);
  const view = render(contexto());
  expect(screen.getByRole('status')).toHaveTextContent('Carregando indicadores');
  view.rerender(contexto({ de: '2026-11-24', ate: '2026-11-24' }));
  expect(await screen.findByText('75,00%')).toBeInTheDocument();
  const antiga = indicadores();
  antiga.ocupacao.percentual = 10;
  await act(async () => { resolverAntiga(json(antiga)); });
  expect(screen.getByText('75,00%')).toBeInTheDocument();
  expect(screen.queryByText('10,00%')).not.toBeInTheDocument();
  expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true);
});

it('cancela a leitura ao desmontar', () => {
  const fetchMock = vi.fn(() => new Promise(() => {}));
  vi.stubGlobal('fetch', fetchMock);
  const view = render(contexto());
  view.unmount();
  expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true);
});

it('a repetição de montagem do StrictMode não mantém a primeira resposta abortada', async () => {
  const fetchMock = vi.fn(async () => json(indicadores()));
  vi.stubGlobal('fetch', fetchMock);
  render(<StrictMode>{contexto()}</StrictMode>);
  expect(await screen.findByText('75,00%')).toBeInTheDocument();
  expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true);
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});

it('usa a data da clínica na entrada mesmo quando o dia UTC já mudou', async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-24T01:30:00Z'));
  const fetchMock = vi.fn(async (url) => {
    const caminho = new URL(url).pathname;
    return json(caminho === '/health' ? { status: 'ok' } : caminho === '/indicadores' ? indicadoresDaUrl(url) : []);
  });
  vi.stubGlobal('fetch', fetchMock);
  render(<RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/'] })} />);
  expect(await screen.findByText('75,00%')).toBeInTheDocument();
  const url = new URL(fetchMock.mock.calls.find(([caminho]) => new URL(caminho).pathname === '/indicadores')[0]);
  expect(url.searchParams.get('de')).toBe('2026-11-23');
  expect(url.searchParams.get('ate')).toBe('2026-11-23');
});

it('o erro 401 de indicadores segue a expiração centralizada da sessão', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url) => {
    const caminho = new URL(url).pathname;
    return caminho === '/indicadores' ? json({ erro: 'Sessão expirada.' }, 401) : json(caminho === '/health' ? { status: 'ok' } : []);
  }));
  const router = createMemoryRouter(routes, { initialEntries: ['/'] });
  render(<RouterProvider router={router} />);
  await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
  expect(await screen.findByRole('heading', { name: 'Acesse sua conta' })).toBeInTheDocument();
  expect(screen.queryByRole('region', { name: 'Indicadores' })).not.toBeInTheDocument();
});
