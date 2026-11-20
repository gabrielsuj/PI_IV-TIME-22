import { beforeEach, expect, it, vi } from 'vitest';
import { buscarIndicadores } from '../services/indicadoresService';
import { entrarComo } from './sessaoTeste';
import { indicadores } from './fixtures/indicadores';

const periodo = { de: '2026-11-23', ate: '2026-11-23' };
const medicoId = '000000000000000000000001';
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

beforeEach(() => {
  sessionStorage.clear();
  entrarComo();
});

it('busca indicadores completos com período, filtro e sessão preservando os valores recebidos', async () => {
  const dados = indicadores({ ...periodo, medicoId });
  const fetchMock = vi.fn(async () => json(dados));
  vi.stubGlobal('fetch', fetchMock);
  expect(await buscarIndicadores({ ...periodo, medicoId })).toEqual(dados);
  expect(fetchMock).toHaveBeenCalledExactlyOnceWith(`http://api.teste/indicadores?de=${periodo.de}&ate=${periodo.ate}&medicoId=${medicoId}`,
    expect.objectContaining({ method: 'GET', headers: expect.objectContaining({ Authorization: 'Bearer token-de-teste' }) }));
});

it('omite o filtro ausente para o servidor resolver o escopo do usuário', async () => {
  const fetchMock = vi.fn(async () => json(indicadores({ medicoId })));
  vi.stubGlobal('fetch', fetchMock);
  expect((await buscarIndicadores(periodo)).medicoId).toBe(medicoId);
  expect(new URL(fetchMock.mock.calls[0][0]).searchParams.has('medicoId')).toBe(false);
});

it.each(['SEM_BASE', 'DADOS_PENDENTES', 'DADOS_INCOMPLETOS'])('preserva percentual nulo e situação %s', async (situacao) => {
  const dados = indicadores();
  dados.confirmacao = { numerador: 0, denominador: situacao === 'SEM_BASE' ? 0 : 6, percentual: null, situacao };
  vi.stubGlobal('fetch', vi.fn(async () => json(dados)));
  expect((await buscarIndicadores(periodo)).confirmacao).toEqual(dados.confirmacao);
});

it.each([
  ['corpo ausente', () => null],
  ['período diferente', (d) => ({ ...d, de: '2026-11-22' })],
  ['fuso incorreto', (d) => ({ ...d, fusoHorario: 'UTC' })],
  ['métrica ausente', (d) => ({ ...d, ocupacao: undefined })],
  ['percentual textual', (d) => ({ ...d, ocupacao: { ...d.ocupacao, percentual: '75' } })],
  ['percentual ausente', (d) => ({ ...d, ocupacao: { ...d.ocupacao, percentual: null } })],
  ['percentual fora do limite', (d) => ({ ...d, ocupacao: { ...d.ocupacao, percentual: 101 } })],
  ['numerador maior que base', (d) => ({ ...d, ocupacao: { ...d.ocupacao, numerador: 9 } })],
  ['estado desconhecido', (d) => ({ ...d, ocupacao: { ...d.ocupacao, situacao: 'DESCONHECIDA' } })],
  ['pendência com percentual', (d) => ({ ...d, confirmacao: { ...d.confirmacao, situacao: 'DADOS_PENDENTES' } })],
  ['sem base com denominador', (d) => ({ ...d, faltas: { ...d.faltas, percentual: null, situacao: 'SEM_BASE' } })],
  ['contagens ausentes', (d) => ({ ...d, contagens: null })],
  ['contagem negativa', (d) => ({ ...d, contagens: { ...d.contagens, agendadas: -1 } })],
  ['qualidade ausente', (d) => ({ ...d, qualidade: null })],
  ['qualidade inválida', (d) => ({ ...d, qualidade: { ...d.qualidade, medicosSemAgenda: '0' } })],
  ['médico inválido', (d) => ({ ...d, medicoId: 'id-login' })],
])('recusa %s em vez de mostrar valores simulados', async (_nome, alterar) => {
  vi.stubGlobal('fetch', vi.fn(async () => json(alterar(indicadores()))));
  await expect(buscarIndicadores(periodo)).rejects.toThrow('Não foi possível ler os indicadores recebidos. Tente novamente.');
});

it('recusa resposta de outro médico para um filtro explícito', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => json(indicadores())));
  await expect(buscarIndicadores({ ...periodo, medicoId })).rejects.toThrow('Não foi possível ler os indicadores recebidos.');
});

it('propaga falha 503 sem substituí-la por indicadores vazios', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => json({ erro: 'Os dados mudaram durante a leitura. Tente novamente.' }, 503)));
  await expect(buscarIndicadores(periodo)).rejects.toMatchObject({ status: 503, message: 'Os dados mudaram durante a leitura. Tente novamente.' });
});

it('cancela a leitura ao desmontar sem repetir requisições', async () => {
  const fetchMock = vi.fn((_url, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(signal.reason), { once: true });
  }));
  vi.stubGlobal('fetch', fetchMock);
  const controller = new AbortController();
  const pedido = buscarIndicadores(periodo, { signal: controller.signal });
  controller.abort();
  await expect(pedido).rejects.toMatchObject({ name: 'AbortError' });
  expect(fetchMock).toHaveBeenCalledTimes(1);
});
