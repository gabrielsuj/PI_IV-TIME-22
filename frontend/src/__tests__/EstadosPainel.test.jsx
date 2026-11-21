/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { routes } from '../routes';
import { entrarComo } from './sessaoTeste';
import { indicadoresDaUrl } from './fixtures/indicadores';
import { agendaPainel, consultaDia, consultaProxima, MEDICO_PAINEL, OUTRO_MEDICO_PAINEL } from './fixtures/consultasPainel';

const BUSCA = `?de=2026-11-20&ate=2026-11-23&dia=2026-11-23&medicoId=${MEDICO_PAINEL}`;
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const contar = (fetchMock, caminho) => fetchMock.mock.calls.filter(([url]) => new URL(url).pathname === caminho).length;
const ROTAS = ['/medicos', '/indicadores', '/agendas', '/consultas', '/consultas/lista', '/health'];

function pendente() {
  let resolver;
  const promessa = new Promise((resolve) => { resolver = resolve; });
  return { promessa, resolver };
}

function resposta(url) {
  const caminho = new URL(url);
  const medicoId = caminho.searchParams.get('medicoId') || MEDICO_PAINEL;
  if (caminho.pathname === '/medicos') return json([
    { id: MEDICO_PAINEL, nome: 'Ana Médica', ativo: true }, { id: OUTRO_MEDICO_PAINEL, nome: 'Bruno Médico', ativo: true },
  ]);
  if (caminho.pathname === '/agendas') return json(agendaPainel(medicoId));
  if (caminho.pathname === '/consultas') return json([consultaDia({ medicoId, pacienteNome: medicoId === OUTRO_MEDICO_PAINEL ? 'Agenda Atual' : 'Agenda Anterior' })]);
  if (caminho.pathname === '/consultas/lista') return json([consultaProxima({ medicoId, pacienteNome: medicoId === OUTRO_MEDICO_PAINEL ? 'Próxima Atual' : 'Próxima Anterior' })]);
  if (caminho.pathname === '/indicadores') {
    const dados = indicadoresDaUrl(url);
    if (medicoId === OUTRO_MEDICO_PAINEL) dados.ocupacao.percentual = 50;
    return json(dados);
  }
  return json({ status: 'ok' });
}

function abrir() {
  const router = createMemoryRouter(routes, { initialEntries: [`/${BUSCA}`] });
  render(<RouterProvider router={router} />);
  return router;
}

beforeEach(() => { sessionStorage.clear(); entrarComo(); });

it.each([
  ['/indicadores', 'Indicadores', 'Carregando indicadores…'],
  ['/consultas', 'Agenda do dia', 'Carregando agenda do dia…'],
  ['/consultas/lista', 'Próximas consultas', 'Carregando próximas consultas…'],
])('a leitura lenta de %s não bloqueia as outras seções', async (rota, titulo, mensagem) => {
  const leitura = pendente();
  let urlPendente;
  vi.stubGlobal('fetch', vi.fn((url) => {
    if (new URL(url).pathname === rota) { urlPendente = url; return leitura.promessa; }
    return Promise.resolve(resposta(url));
  }));
  abrir();
  expect(await screen.findByText(mensagem)).toBeInTheDocument();
  if (rota !== '/indicadores') expect(await screen.findByText('75,00%')).toBeInTheDocument();
  if (rota !== '/consultas') expect(await screen.findByText('Agenda Anterior')).toBeInTheDocument();
  if (rota !== '/consultas/lista') expect(await screen.findByText('Próxima Anterior')).toBeInTheDocument();
  expect(screen.getByRole('region', { name: titulo })).toHaveAttribute('aria-busy', 'true');
  await act(async () => leitura.resolver(resposta(urlPendente)));
  expect(screen.getByRole('region', { name: titulo })).toHaveAttribute('aria-busy', 'false');
});

it.each([
  ['/indicadores', 'Tentar novamente', '75,00%', ['/indicadores']],
  ['/agendas', 'Tentar carregar agenda novamente', 'Agenda Anterior', ['/agendas', '/consultas']],
  ['/consultas', 'Tentar carregar agenda novamente', 'Agenda Anterior', ['/agendas', '/consultas']],
  ['/consultas/lista', 'Tentar carregar próximas consultas novamente', 'Próxima Anterior', ['/consultas/lista']],
])('nova tentativa de %s preserva os filtros e renova somente a sua seção', async (rota, botao, sucesso, renovadas) => {
  let falhar = true;
  const fetchMock = vi.fn(async (url) => {
    if (new URL(url).pathname === rota && falhar) { falhar = false; return json({ erro: 'Falha isolada da seção.' }, 503); }
    return resposta(url);
  });
  vi.stubGlobal('fetch', fetchMock);
  const user = userEvent.setup();
  const router = abrir();
  expect(await screen.findByRole('alert')).toHaveTextContent('Falha isolada da seção.');
  await waitFor(() => expect(contar(fetchMock, '/consultas/lista')).toBe(1));
  const antes = Object.fromEntries(ROTAS.map((caminho) => [caminho, contar(fetchMock, caminho)]));
  await user.click(screen.getByRole('button', { name: botao }));
  expect(await screen.findByText(sucesso)).toBeInTheDocument();
  for (const caminho of ROTAS) expect(contar(fetchMock, caminho)).toBe(antes[caminho] + Number(renovadas.includes(caminho)));
  expect(router.state.location.search).toBe(BUSCA);
  expect(screen.getByLabelText('Médico do painel')).toHaveValue(MEDICO_PAINEL);
  expect(fetchMock.mock.calls.every(([, options]) => options.method === 'GET')).toBe(true);
});

// As respostas pendentes isolam cada seção para conferir que falha ou nova tentativa
// não dispare leituras nas outras.
it('respostas anteriores das três seções não substituem a seleção nova', async () => {
  const antigas = new Map(['/indicadores', '/consultas', '/consultas/lista'].map((rota) => [rota, pendente()]));
  const urlsAntigas = new Map();
  const fetchMock = vi.fn((url) => {
    const parsed = new URL(url);
    if (parsed.searchParams.get('medicoId') === MEDICO_PAINEL && antigas.has(parsed.pathname)) {
      urlsAntigas.set(parsed.pathname, url);
      return antigas.get(parsed.pathname).promessa;
    }
    return Promise.resolve(resposta(url));
  });
  vi.stubGlobal('fetch', fetchMock);
  const user = userEvent.setup();
  abrir();
  await waitFor(() => expect(urlsAntigas.size).toBe(3));
  await user.selectOptions(screen.getByLabelText('Médico do painel'), OUTRO_MEDICO_PAINEL);
  expect(await screen.findByText('50,00%')).toBeInTheDocument();
  expect(await screen.findByText('Agenda Atual')).toBeInTheDocument();
  expect(await screen.findByText('Próxima Atual')).toBeInTheDocument();
  await act(async () => {
    for (const [rota, leitura] of antigas) leitura.resolver(resposta(urlsAntigas.get(rota)));
  });
  expect(screen.queryByText('75,00%')).not.toBeInTheDocument();
  expect(screen.queryByText('Agenda Anterior')).not.toBeInTheDocument();
  expect(screen.queryByText('Próxima Anterior')).not.toBeInTheDocument();
  expect(screen.getByText('50,00%')).toBeInTheDocument();
  expect(contar(fetchMock, '/medicos')).toBe(1);
});

it('catálogo inválido informa falha sem derrubar as três seções e sua tentativa é independente', async () => {
  let primeira = true;
  const fetchMock = vi.fn(async (url) => {
    if (new URL(url).pathname === '/medicos' && primeira) { primeira = false; return json([{ id: {}, nome: {} }]); }
    return resposta(url);
  });
  vi.stubGlobal('fetch', fetchMock);
  const user = userEvent.setup();
  const router = abrir();
  expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível ler a lista de médicos recebida.');
  expect(await screen.findByText('75,00%')).toBeInTheDocument();
  expect(await screen.findByText('Agenda Anterior')).toBeInTheDocument();
  expect(await screen.findByText('Próxima Anterior')).toBeInTheDocument();
  const antes = Object.fromEntries(ROTAS.map((rota) => [rota, contar(fetchMock, rota)]));
  await user.click(screen.getByRole('button', { name: 'Tentar carregar médicos novamente' }));
  expect(await screen.findByRole('option', { name: 'Ana Médica' })).toBeInTheDocument();
  for (const rota of ROTAS) expect(contar(fetchMock, rota)).toBe(antes[rota] + Number(rota === '/medicos'));
  expect(router.state.location.search).toBe(BUSCA);
});

it('lista de médicos vazia é distinta de erro e não impede a leitura dos indicadores', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url) => new URL(url).pathname === '/medicos' ? json([]) : resposta(url)));
  abrir();
  expect(await screen.findByText('Nenhum médico cadastrado.')).toBeInTheDocument();
  expect(await screen.findByText('75,00%')).toBeInTheDocument();
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});

it('período vazio mantém as taxas e os vazios corretos por seção', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url) => {
    const caminho = new URL(url).pathname;
    if (caminho === '/consultas' || caminho === '/consultas/lista') return json([]);
    if (caminho === '/indicadores') {
      const dados = indicadoresDaUrl(url);
      for (const campo of Object.keys(dados.contagens)) dados.contagens[campo] = campo === 'capacidade' ? 8 : 0;
      dados.ocupacao = { numerador: 0, denominador: 8, percentual: 0, situacao: 'DISPONIVEL' };
      for (const campo of ['faltas', 'cancelamento', 'confirmacao', 'preenchimentoFila'])
        dados[campo] = { numerador: 0, denominador: 0, percentual: null, situacao: 'SEM_BASE' };
      return json(dados);
    }
    return resposta(url);
  }));
  abrir();
  expect(await screen.findByText('Nenhuma consulta iniciada no período selecionado.')).toBeInTheDocument();
  expect(await screen.findByText('Nenhuma consulta na agenda deste dia.')).toBeInTheDocument();
  expect(await screen.findByText('Nenhuma próxima consulta encontrada.')).toBeInTheDocument();
  expect(within(screen.getByRole('region', { name: 'Ocupação' })).getByText('0,00%')).toBeInTheDocument();
  expect(screen.getAllByText('Sem base para cálculo')).toHaveLength(4);
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});
