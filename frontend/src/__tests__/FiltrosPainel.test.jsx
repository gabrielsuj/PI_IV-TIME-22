import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { routes } from '../routes';
import { dataPainelValida, erroPeriodoPainel } from '../utils/painel';
import { entrarComo } from './sessaoTeste';
import { indicadoresDaUrl } from './fixtures/indicadores';
import { agendaPainel, consultaDia, consultaProxima, MEDICO_PAINEL, OUTRO_MEDICO_PAINEL } from './fixtures/consultasPainel';

const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const chamadas = (fetchMock, caminho) => fetchMock.mock.calls.map(([url]) => new URL(url)).filter((url) => url.pathname === caminho);
const parametros = (fetchMock, caminho) => Object.fromEntries(chamadas(fetchMock, caminho).at(-1).searchParams);

function mockApi({ semVinculo = false, catalogoPendente } = {}) {
  const fetchMock = vi.fn(async (caminho) => {
    const url = new URL(caminho);
    const filtro = url.searchParams.get('medicoId');
    const cadastro = filtro && filtro !== 'meu' ? filtro : MEDICO_PAINEL;
    if (url.pathname === '/health') return json({ status: 'ok' });
    if (url.pathname === '/medicos') {
      await catalogoPendente;
      return json([
        { id: MEDICO_PAINEL, nome: 'Ana Médica', ativo: true },
        { id: OUTRO_MEDICO_PAINEL, nome: 'Bruno Médico', ativo: false },
      ]);
    }
    if (semVinculo) return json({ erro: 'Seu login não está ligado a um cadastro de médico.' }, 403);
    if (![MEDICO_PAINEL, OUTRO_MEDICO_PAINEL].includes(cadastro)) return json({ erro: 'Médico não encontrado.' }, 404);
    if (cadastro === OUTRO_MEDICO_PAINEL && ['/agendas', '/consultas'].includes(url.pathname)) {
      return json({ erro: 'Médico não encontrado ou inativo.' }, 404);
    }
    if (url.pathname === '/indicadores') return json(indicadoresDaUrl(caminho));
    if (url.pathname === '/agendas') return json(agendaPainel(cadastro, 'UTC'));
    if (url.pathname === '/consultas') return json([consultaDia({ medicoId: cadastro })]);
    if (url.pathname === '/consultas/lista') return json([consultaProxima({ medicoId: cadastro })]);
    return json({}, 404);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function abrir(query = '') {
  const router = createMemoryRouter(routes, { initialEntries: [`/?de=2026-11-20&ate=2026-11-23&dia=2026-11-22${query}`] });
  render(<RouterProvider router={router} />);
  return router;
}

beforeEach(() => { sessionStorage.clear(); entrarComo(); });

it('aplica o mesmo médico às três seções com datas e significados explícitos', async () => {
  const fetchMock = mockApi();
  abrir(`&medicoId=${MEDICO_PAINEL}`);
  expect(await screen.findByText('Paula da Agenda')).toBeInTheDocument();
  expect(await screen.findByText('75,00%')).toBeInTheDocument();
  expect(screen.getByLabelText('Médico do painel')).toHaveValue(MEDICO_PAINEL);
  expect(screen.getByText('Escopo do painel: Ana Médica.')).toBeInTheDocument();
  expect(parametros(fetchMock, '/indicadores')).toEqual({ de: '2026-11-20', ate: '2026-11-23', medicoId: MEDICO_PAINEL });
  expect(parametros(fetchMock, '/consultas')).toEqual({ de: '2026-11-22', ate: '2026-11-22', medicoId: MEDICO_PAINEL, fusoHorario: 'America/Sao_Paulo' });
  expect(parametros(fetchMock, '/consultas/lista')).toEqual({ periodo: 'proximas', medicoId: MEDICO_PAINEL, somenteAtivas: 'true' });
  expect(screen.getByText(/consultas em andamento ou futuras a partir de agora/)).toBeInTheDocument();
  expect(screen.getByText('Horários exibidos em America/Sao_Paulo.')).toBeInTheDocument();
});

it('mantém indicadores e consultas da seleção inativa, sem pedir a agenda indisponível', async () => {
  const fetchMock = mockApi();
  const user = userEvent.setup();
  const router = abrir();
  await screen.findByRole('option', { name: 'Bruno Médico (inativo)' });
  await user.selectOptions(screen.getByLabelText('Médico do painel'), OUTRO_MEDICO_PAINEL);
  expect(await screen.findByText(/O médico selecionado está inativo/)).toBeInTheDocument();
  for (const rota of ['/indicadores', '/consultas/lista']) expect(parametros(fetchMock, rota).medicoId).toBe(OUTRO_MEDICO_PAINEL);
  for (const rota of ['/agendas', '/consultas']) expect(chamadas(fetchMock, rota)).toHaveLength(0);
  expect(screen.getByRole('button', { name: 'Abrir calendário' })).toBeDisabled();
  expect(screen.getByLabelText('Início dos indicadores')).toHaveValue('2026-11-20');
  expect(screen.getByLabelText('Fim dos indicadores')).toHaveValue('2026-11-23');
  expect(screen.getByLabelText('Dia da agenda')).toHaveValue('2026-11-22');
  expect(router.state.location.search).toContain(`medicoId=${OUTRO_MEDICO_PAINEL}`);
  await user.selectOptions(screen.getByLabelText('Médico do painel'), MEDICO_PAINEL);
  expect(await screen.findByText('Paula da Agenda')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Abrir calendário' })).toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText('Médico do painel'), '');
  await waitFor(() => expect(parametros(fetchMock, '/indicadores').medicoId).toBeUndefined());
  expect(parametros(fetchMock, '/consultas/lista').medicoId).toBeUndefined();
  expect(screen.queryByText('Paula da Agenda')).not.toBeInTheDocument();
  expect(screen.getByText('Selecione um médico no filtro do painel para consultar a agenda do dia.')).toBeInTheDocument();
});

it('aguarda o catálogo ao abrir o painel direto com um médico inativo', async () => {
  let liberarCatalogo;
  const catalogoPendente = new Promise((resolve) => { liberarCatalogo = resolve; });
  const fetchMock = mockApi({ catalogoPendente });
  abrir(`&medicoId=${OUTRO_MEDICO_PAINEL}`);
  expect(await screen.findByText('Carregando agenda do dia…')).toBeInTheDocument();
  expect(chamadas(fetchMock, '/agendas')).toHaveLength(0);
  await act(async () => { liberarCatalogo(); });
  expect(await screen.findByText(/O médico selecionado está inativo/)).toBeInTheDocument();
  expect(chamadas(fetchMock, '/agendas')).toHaveLength(0);
  expect(chamadas(fetchMock, '/consultas')).toHaveLength(0);
});

it('datas distintas renovam somente as seções que declaram esses períodos', async () => {
  const fetchMock = mockApi();
  abrir(`&medicoId=${MEDICO_PAINEL}`);
  await screen.findByText('Paula da Agenda');
  const calendarioAntes = chamadas(fetchMock, '/consultas').length;
  const proximasAntes = chamadas(fetchMock, '/consultas/lista').length;
  fireEvent.change(screen.getByLabelText('Início dos indicadores'), { target: { value: '2026-11-19' } });
  await waitFor(() => expect(parametros(fetchMock, '/indicadores').de).toBe('2026-11-19'));
  expect(chamadas(fetchMock, '/consultas')).toHaveLength(calendarioAntes);
  expect(chamadas(fetchMock, '/consultas/lista')).toHaveLength(proximasAntes);
  const indicadoresAntes = chamadas(fetchMock, '/indicadores').length;
  fireEvent.change(screen.getByLabelText('Dia da agenda'), { target: { value: '2026-11-24' } });
  await waitFor(() => expect(parametros(fetchMock, '/consultas').de).toBe('2026-11-24'));
  expect(chamadas(fetchMock, '/indicadores')).toHaveLength(indicadoresAntes);
  expect(chamadas(fetchMock, '/consultas/lista')).toHaveLength(proximasAntes);
});

it.each([OUTRO_MEDICO_PAINEL, 'invalido'])('o médico ignora o alvo %s da URL e usa somente o próprio escopo', async (alvo) => {
  entrarComo({ id: 'id-login-nao-e-cadastro', nome: 'Ana', perfil: 'MEDICO' });
  const fetchMock = mockApi();
  abrir(`&medicoId=${alvo}`);
  expect(await screen.findByText('Paula da Agenda')).toBeInTheDocument();
  expect(screen.queryByLabelText('Médico do painel')).not.toBeInTheDocument();
  expect(screen.getByText('Escopo do painel: Seus atendimentos.')).toBeInTheDocument();
  expect(chamadas(fetchMock, '/medicos')).toHaveLength(0);
  expect(parametros(fetchMock, '/consultas').medicoId).toBe('meu');
  expect(parametros(fetchMock, '/agendas').medicoId).toBe('meu');
  expect(parametros(fetchMock, '/indicadores').medicoId).toBeUndefined();
  expect(parametros(fetchMock, '/consultas/lista').medicoId).toBeUndefined();
  expect(fetchMock.mock.calls.every(([url]) => !url.includes(alvo) && !url.includes('id-login-nao-e-cadastro'))).toBe(true);
});

it('médico sem vínculo recebe a recusa das fontes sem tentar um escopo global', async () => {
  entrarComo({ id: 'sem-cadastro', nome: 'Médico', perfil: 'MEDICO' });
  const fetchMock = mockApi({ semVinculo: true });
  abrir();
  await waitFor(() => expect(screen.getAllByText('Seu login não está ligado a um cadastro de médico.')).toHaveLength(3));
  expect(screen.queryByText('75,00%')).not.toBeInTheDocument();
  expect(chamadas(fetchMock, '/agendas')).toHaveLength(1);
  expect(chamadas(fetchMock, '/consultas')).toHaveLength(0);
  expect(chamadas(fetchMock, '/indicadores')).toHaveLength(1);
  expect(chamadas(fetchMock, '/consultas/lista')).toHaveLength(1);
});

it('filtro inválido da secretaria bloqueia as fontes sem ampliar a consulta para todos', async () => {
  const fetchMock = mockApi();
  abrir('&medicoId=invalido');
  expect(await screen.findByRole('alert')).toHaveTextContent('O filtro de médico é inválido.');
  for (const rota of ['/indicadores', '/consultas', '/consultas/lista', '/agendas']) expect(chamadas(fetchMock, rota)).toHaveLength(0);
});

it('cadastro não encontrado mantém o filtro e o erro, sem consultar todos', async () => {
  const fetchMock = mockApi();
  const desconhecido = 'ffffffffffffffffffffffff';
  const router = abrir(`&medicoId=${desconhecido}`);
  await waitFor(() => expect(screen.getAllByText('Médico não encontrado.')).toHaveLength(3));
  expect(router.state.location.search).toContain(desconhecido);
  for (const rota of ['/indicadores', '/agendas', '/consultas/lista']) expect(parametros(fetchMock, rota).medicoId).toBe(desconhecido);
});

it.each([
  ['de=2026-11-24&ate=2026-11-23', 'O fim dos indicadores deve ser igual ou posterior ao início.'],
  ['de=2026-11-01&ate=2027-01-02', 'O período dos indicadores deve ter no máximo 62 dias.'],
  ['de=&ate=2026-11-23', 'Informe datas válidas para o período dos indicadores.'],
  ['de=2026-02-30&ate=2026-11-23', 'Informe datas válidas para o período dos indicadores.'],
])('período inválido %s suspende somente os indicadores', async (query, mensagem) => {
  const fetchMock = mockApi();
  const router = createMemoryRouter(routes, { initialEntries: [`/?${query}&dia=2026-11-22&medicoId=${MEDICO_PAINEL}`] });
  render(<RouterProvider router={router} />);
  expect(screen.getByText(mensagem)).toBeInTheDocument();
  expect(await screen.findByText('Paula da Agenda')).toBeInTheDocument();
  expect(await screen.findByText('Bruno da Lista')).toBeInTheDocument();
  expect(chamadas(fetchMock, '/indicadores')).toHaveLength(0);
});

it('dia inválido não oculta os indicadores e próximas consultas válidos', async () => {
  const fetchMock = mockApi();
  const router = createMemoryRouter(routes, { initialEntries: [`/?dia=2026-02-30&medicoId=${MEDICO_PAINEL}`] });
  render(<RouterProvider router={router} />);
  expect(screen.getByText('Informe uma data válida para a agenda.')).toBeInTheDocument();
  expect(await screen.findByText('75,00%')).toBeInTheDocument();
  expect(await screen.findByText('Bruno da Lista')).toBeInTheDocument();
  expect(chamadas(fetchMock, '/consultas')).toHaveLength(0);
});

it('voltar à página recupera os filtros da URL', async () => {
  mockApi();
  const router = abrir(`&medicoId=${MEDICO_PAINEL}`);
  await screen.findByText('Paula da Agenda');
  await act(async () => { await router.navigate('/perfis'); });
  await act(async () => { await router.navigate(-1); });
  expect(await screen.findByText('Paula da Agenda')).toBeInTheDocument();
  expect(screen.getByLabelText('Médico do painel')).toHaveValue(MEDICO_PAINEL);
  expect(screen.getByLabelText('Início dos indicadores')).toHaveValue('2026-11-20');
  expect(screen.getByLabelText('Dia da agenda')).toHaveValue('2026-11-22');
});

it('paciente não acessa os filtros nem as fontes mesmo com parâmetros operacionais na URL', async () => {
  entrarComo({ id: 'paciente', nome: 'Paciente', perfil: 'PACIENTE' });
  const fetchMock = mockApi();
  abrir(`&medicoId=${MEDICO_PAINEL}`);
  expect(await screen.findByText('API disponível')).toBeInTheDocument();
  expect(screen.queryByLabelText('Médico do painel')).not.toBeInTheDocument();
  expect(fetchMock.mock.calls.every(([url]) => new URL(url).pathname === '/health')).toBe(true);
});

it('valida datas civis e o limite inclusivo sem depender do fuso do navegador', () => {
  expect(dataPainelValida('2024-02-29')).toBe(true);
  expect(dataPainelValida('2026-02-29')).toBe(false);
  expect(dataPainelValida('0000-01-01')).toBe(false);
  expect(erroPeriodoPainel('2026-11-01', '2027-01-01')).toBeNull();
  expect(erroPeriodoPainel('2026-11-01', '2027-01-02')).toContain('62 dias');
  expect(erroPeriodoPainel('2018-11-03', '2018-11-05')).toBeNull();
});
