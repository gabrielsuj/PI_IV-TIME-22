import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { routes } from '../routes';
import { entrarComo } from './sessaoTeste';

// #44: bloqueio ou nova jornada que atinge consultas marcadas.
const ANA = { id: 'm1', nome: 'Ana Martins', crmNumero: '000123', crmUf: 'SP', especialidadeIds: ['e1'], ativo: true };
const AGENDA = { id: 'a1', medicoId: 'm1', fusoHorario: 'America/Sao_Paulo', duracaoMinutos: 30,
  jornadas: [{ diaSemana: 1, faixas: [{ inicioMinuto: 480, fimMinuto: 720 }] }] };
const AFETADA = { id: 'c1', medicoId: 'm1', pacienteId: 'p1', pacienteNome: 'Paulo Paciente', especialidadeId: 'e1',
  especialidadeNome: 'Clínica Geral', inicio: '2026-11-16T09:00', fim: '2026-11-16T09:30', status: 'CONFIRMADA' };

const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const conflito = (erro) => json({ status: 409, erro, consultas: [AFETADA] }, 409);

function mockApi(rotas) {
  const fetchMock = vi.fn(async (url, init = {}) => {
    const u = new URL(url);
    const rota = rotas[`${init.method ?? 'GET'} ${u.pathname}`];
    if (!rota) return json({ status: 404, erro: 'Rota não encontrada' }, 404);
    return rota({ url: u, body: init.body ? JSON.parse(init.body) : undefined });
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

const chamadas = (fetchMock, metodo, caminho) => fetchMock.mock.calls
  .filter(([url, init = {}]) => (init.method ?? 'GET') === metodo && new URL(url).pathname === caminho)
  .map(([url, init]) => ({ url: new URL(url), body: init.body ? JSON.parse(init.body) : undefined }));

function renderAt(path) {
  render(<RouterProvider router={createMemoryRouter(routes, { initialEntries: [path] })} />);
}

beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value() { this.open = true; } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value() { this.open = false; } });
  sessionStorage.clear();
  entrarComo();
});

const baseApi = (extra) => ({
  'GET /medicos': () => json([ANA]),
  'GET /agendas': () => json(AGENDA),
  'GET /bloqueios': () => json([]),
  ...extra,
});

async function pedirBloqueio(user) {
  await user.type(await screen.findByLabelText(/Data de início/), '2026-11-16');
  await user.click(screen.getByLabelText('Dia inteiro'));
  await user.click(screen.getByRole('button', { name: 'Adicionar bloqueio' }));
}

describe('bloqueio sobre consultas marcadas', () => {
  it('mostra as consultas atingidas e só bloqueia depois da confirmação', async () => {
    const fetchMock = mockApi(baseApi({
      'POST /bloqueios': ({ url, body }) => (url.searchParams.get('confirmar') === 'true'
        ? json({ id: 'b9', medicoId: 'm1', ...body }, 201)
        : conflito('O período tem 1 consulta(s) marcada(s). Confirme para bloquear mesmo assim; elas precisarão ser remarcadas.')),
    }));
    const user = userEvent.setup();
    renderAt('/agenda?medicoId=m1');
    await pedirBloqueio(user);

    const aviso = await screen.findByText(/O período tem 1 consulta\(s\) marcada\(s\)/);
    const painel = aviso.closest('.conflito-consultas');
    expect(within(painel).getByText('16/11/2026, 09:00 às 09:30')).toBeInTheDocument();
    expect(painel).toHaveTextContent('Paulo Paciente · Clínica Geral · confirmada');
    expect(within(painel).getByRole('link', { name: 'calendário' })).toHaveAttribute('href', '/calendario?medicoId=m1&data=2026-11-16');
    expect(screen.queryByRole('button', { name: 'Adicionar bloqueio' })).not.toBeInTheDocument();

    await user.click(within(painel).getByRole('button', { name: 'Bloquear mesmo assim' }));

    expect(await screen.findByText('Bloqueio cadastrado. 1 consulta(s) precisam ser remarcadas.')).toBeInTheDocument();
    const [primeira, confirmada] = chamadas(fetchMock, 'POST', '/bloqueios');
    expect(primeira.url.searchParams.get('confirmar')).toBeNull();
    expect(confirmada.url.searchParams.get('confirmar')).toBe('true');
    expect(confirmada.body).toEqual(primeira.body);
    expect(Object.keys(confirmada.body).sort()).toEqual(['fim', 'inicio', 'medicoId']);
  });

  it('volta para o formulário sem bloquear', async () => {
    const fetchMock = mockApi(baseApi({ 'POST /bloqueios': () => conflito('O período tem 1 consulta(s) marcada(s).') }));
    const user = userEvent.setup();
    renderAt('/agenda?medicoId=m1');
    await pedirBloqueio(user);

    await user.click(await screen.findByRole('button', { name: 'Voltar e ajustar' }));

    expect(screen.queryByText(/consulta\(s\) marcada\(s\)/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Adicionar bloqueio' })).toBeInTheDocument();
    expect(chamadas(fetchMock, 'POST', '/bloqueios')).toHaveLength(1);
  });

  it('outros erros continuam aparecendo como antes', async () => {
    mockApi(baseApi({ 'POST /bloqueios': () => json({ status: 409, erro: 'Já existe um bloqueio nesse período' }, 409) }));
    const user = userEvent.setup();
    renderAt('/agenda?medicoId=m1');
    await pedirBloqueio(user);

    expect(await screen.findByText('Já existe um bloqueio nesse período')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Bloquear mesmo assim' })).not.toBeInTheDocument();
  });
});

describe('nova jornada que deixa consultas de fora', () => {
  it('mostra as consultas e salva com confirmação', async () => {
    const fetchMock = mockApi(baseApi({
      'PUT /agendas': ({ url, body }) => (url.searchParams.get('confirmar') === 'true'
        ? json({ ...AGENDA, ...body })
        : conflito('A nova jornada deixa 1 consulta(s) marcada(s) fora do horário de atendimento. Confirme para salvar mesmo assim; elas precisarão ser remarcadas.')),
    }));
    const user = userEvent.setup();
    renderAt('/agenda?medicoId=m1');

    await user.click(await screen.findByRole('button', { name: 'Salvar jornada' }));
    const painel = (await screen.findByText(/A nova jornada deixa 1 consulta/)).closest('.conflito-consultas');
    expect(painel).toHaveTextContent('Paulo Paciente');
    await user.click(within(painel).getByRole('button', { name: 'Salvar mesmo assim' }));

    expect(await screen.findByText('Agenda salva. 1 consulta(s) precisam ser remarcadas.')).toBeInTheDocument();
    await waitFor(() => expect(chamadas(fetchMock, 'PUT', '/agendas').at(-1).url.searchParams.get('confirmar')).toBe('true'));
    expect(chamadas(fetchMock, 'PUT', '/agendas')[0].url.searchParams.get('confirmar')).toBeNull();
  });
});
