import { beforeEach, expect, it, vi } from 'vitest';
import { buscarAgendaDoDia, buscarProximasConsultas } from '../services/painelConsultasService';
import { entrarComo } from './sessaoTeste';
import { agendaPainel, consultaDia, consultaProxima, MEDICO_PAINEL, OUTRO_MEDICO_PAINEL, proximasEmQuantidade } from './fixtures/consultasPainel';

const dia = '2026-11-23';
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

beforeEach(() => { sessionStorage.clear(); entrarComo(); });

it('busca a agenda e as consultas do calendário com sessão, médico e dia exatos', async () => {
  const consulta = consultaDia();
  const fetchMock = vi.fn(async (url) => json(new URL(url).pathname === '/agendas' ? agendaPainel() : [consulta]));
  vi.stubGlobal('fetch', fetchMock);
  const resultado = await buscarAgendaDoDia({ medicoId: MEDICO_PAINEL, dia });
  expect(resultado).toEqual({ consultas: [consulta], fusoHorario: 'America/Sao_Paulo', semAgenda: false });
  expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
    `http://api.teste/agendas?medicoId=${MEDICO_PAINEL}`,
    `http://api.teste/consultas?medicoId=${MEDICO_PAINEL}&de=${dia}&ate=${dia}`,
  ]);
  expect(fetchMock.mock.calls.every(([, options]) => options.method === 'GET'
    && options.headers.Authorization === 'Bearer token-de-teste')).toBe(true);
});

it('não limita a agenda do dia aos cem registros da outra listagem', async () => {
  const consultas = Array.from({ length: 101 }, (_, i) => consultaDia({ id: `consulta-${String(i).padStart(3, '0')}` })).reverse();
  const antes = JSON.stringify(consultas);
  vi.stubGlobal('fetch', vi.fn(async (url) => json(new URL(url).pathname === '/agendas' ? agendaPainel() : consultas)));
  const resultado = await buscarAgendaDoDia({ medicoId: MEDICO_PAINEL, dia });
  expect(resultado.consultas).toHaveLength(101);
  expect(resultado.consultas[0].id).toBe('consulta-000');
  expect(JSON.stringify(consultas)).toBe(antes);
});

it('preserva o fuso e as datas locais do calendário, inclusive uma consulta que cruza meia-noite', async () => {
  const consulta = consultaDia({ inicio: '2026-11-22T23:45', fim: '2026-11-23T00:15' });
  vi.stubGlobal('fetch', vi.fn(async (url) => json(new URL(url).pathname === '/agendas' ? agendaPainel(MEDICO_PAINEL, 'America/Manaus') : [consulta])));
  expect(await buscarAgendaDoDia({ medicoId: MEDICO_PAINEL, dia })).toEqual({
    consultas: [consulta], fusoHorario: 'America/Manaus', semAgenda: false,
  });
});

it('agenda não configurada é sinalizada sem esconder consultas já marcadas', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url) => new URL(url).pathname === '/agendas'
    ? json({ erro: 'Agenda não configurada' }, 404) : json([consultaDia()])));
  const dados = await buscarAgendaDoDia({ medicoId: MEDICO_PAINEL, dia });
  expect(dados.semAgenda).toBe(true);
  expect(dados.consultas).toHaveLength(1);
  expect(dados.fusoHorario).toBe('America/Sao_Paulo');
});

it('meu é resolvido pela API e o cadastro retornado confere os vínculos do calendário', async () => {
  const fetchMock = vi.fn(async (url) => json(new URL(url).pathname === '/agendas' ? agendaPainel() : [consultaDia()]));
  vi.stubGlobal('fetch', fetchMock);
  expect((await buscarAgendaDoDia({ medicoId: 'meu', dia })).consultas).toHaveLength(1);
  expect(fetchMock.mock.calls.every(([url]) => new URL(url).searchParams.get('medicoId') === 'meu')).toBe(true);
});

it.each(['meu', MEDICO_PAINEL])('recusa consulta de outro médico para o filtro %s', async (medicoId) => {
  vi.stubGlobal('fetch', vi.fn(async (url) => json(new URL(url).pathname === '/agendas'
    ? agendaPainel() : [consultaDia({ medicoId: OUTRO_MEDICO_PAINEL })])));
  await expect(buscarAgendaDoDia({ medicoId, dia })).rejects.toThrow('não correspondem ao médico selecionado');
});

it.each([0, 1, 5, 6, 99, 100])('ordena e resume %s próximas consultas, preservando o aviso de limite', async (quantidade) => {
  const consultas = proximasEmQuantidade(quantidade).reverse();
  const fetchMock = vi.fn(async () => json(consultas));
  vi.stubGlobal('fetch', fetchMock);
  const resultado = await buscarProximasConsultas();
  expect(resultado.consultas).toHaveLength(Math.min(quantidade, 5));
  expect(resultado.totalRecebido).toBe(quantidade);
  expect(resultado.limiteAtingido).toBe(quantidade === 100);
  if (quantidade) expect(resultado.consultas[0].id).toBe('consulta-000');
  expect(fetchMock.mock.calls[0][0]).toBe('http://api.teste/consultas/lista?periodo=proximas');
});

it('o filtro das próximas é opcional e nunca usa a lista como calendário ou indicador', async () => {
  const fetchMock = vi.fn(async () => json([consultaProxima()]));
  vi.stubGlobal('fetch', fetchMock);
  await buscarProximasConsultas({ medicoId: MEDICO_PAINEL });
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(fetchMock.mock.calls[0][0]).toBe(`http://api.teste/consultas/lista?periodo=proximas&medicoId=${MEDICO_PAINEL}`);
});

it('recusa próximas consultas de outro médico quando existe filtro explícito', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => json([consultaProxima({ medicoId: OUTRO_MEDICO_PAINEL })])));
  await expect(buscarProximasConsultas({ medicoId: MEDICO_PAINEL })).rejects.toThrow('listagem de próximas consultas recebida é inválida');
});

it.each([
  ['corpo ausente', null], ['objeto', {}], ['linha nula', [null]],
  ['ID repetido', [consultaProxima(), consultaProxima()]],
  ['status desconhecido', [consultaProxima({ status: 'INDEFINIDA' })]],
  ['nome inválido', [consultaProxima({ pacienteNome: {} })]],
  ['data local em vez de instante', [consultaProxima({ inicio: '2026-11-23T10:00' })]],
  ['fim antes do início', [consultaProxima({ fim: '2026-11-23T10:00:00Z' })]],
  ['mais que o limite', proximasEmQuantidade(101)],
])('recusa %s nas próximas sem inventar vazio', async (_nome, dados) => {
  vi.stubGlobal('fetch', vi.fn(async () => json(dados)));
  await expect(buscarProximasConsultas()).rejects.toThrow();
});

it.each([null, {}, [consultaDia({ inicio: '2026-02-30T09:00' })], [consultaDia({ inicio: '2026-11-23T12:00:00Z' })]].map((dados) => [dados]))('recusa calendário fora do contrato %j', async (dados) => {
    vi.stubGlobal('fetch', vi.fn(async (url) => json(new URL(url).pathname === '/agendas' ? agendaPainel() : dados)));
    await expect(buscarAgendaDoDia({ medicoId: MEDICO_PAINEL, dia })).rejects.toThrow('Não foi possível ler as consultas recebidas.');
  });

it.each([{}, agendaPainel(MEDICO_PAINEL, 'Invalido/Local')])('recusa agenda inválida %j antes de buscar consultas', async (agenda) => {
  const fetchMock = vi.fn(async () => json(agenda));
  vi.stubGlobal('fetch', fetchMock);
  await expect(buscarAgendaDoDia({ medicoId: MEDICO_PAINEL, dia })).rejects.toThrow();
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

it('não usa o fuso de uma agenda vinculada a outro médico', async () => {
  const fetchMock = vi.fn(async () => json(agendaPainel(OUTRO_MEDICO_PAINEL, 'America/Manaus')));
  vi.stubGlobal('fetch', fetchMock);
  await expect(buscarAgendaDoDia({ medicoId: MEDICO_PAINEL, dia })).rejects.toThrow('A agenda recebida não corresponde ao médico selecionado.');
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

it.each(['/agendas', '/consultas', '/consultas/lista'])('propaga falha de %s sem retornar listas vazias', async (caminho) => {
  vi.stubGlobal('fetch', vi.fn(async (url) => new URL(url).pathname === caminho
    ? json({ erro: 'Serviço temporariamente indisponível.' }, 503) : json(agendaPainel())));
  const pedido = caminho === '/consultas/lista' ? buscarProximasConsultas() : buscarAgendaDoDia({ medicoId: MEDICO_PAINEL, dia });
  await expect(pedido).rejects.toMatchObject({ status: 503, message: 'Serviço temporariamente indisponível.' });
});

it('interrompe a cadeia antes de buscar o calendário se a leitura da agenda foi abortada', async () => {
  let resolver;
  const fetchMock = vi.fn(() => new Promise((resolve) => { resolver = resolve; }));
  vi.stubGlobal('fetch', fetchMock);
  const controller = new AbortController();
  const pedido = buscarAgendaDoDia({ medicoId: 'meu', dia }, { signal: controller.signal });
  controller.abort();
  resolver(json(agendaPainel()));
  await expect(pedido).rejects.toMatchObject({ name: 'AbortError' });
  expect(fetchMock).toHaveBeenCalledTimes(1);
});
