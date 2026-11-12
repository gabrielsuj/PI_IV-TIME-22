import { beforeEach, describe, expect, it, vi } from 'vitest';
import { buscarCatalogosAgendamento } from '../services/catalogoAgendamentoService';
import { entrarComo } from './sessaoTeste';

const especialidades = [
  { id: 'e1', nome: 'Cardiologia', ativa: true },
  { id: 'e2', nome: 'Clínica Geral', ativa: false },
];
const medicos = [
  { id: 'm1', nome: 'Ana Teste', crmNumero: '000123', crmUf: 'SP', especialidadeIds: ['e1'], ativo: true },
  { id: 'm2', nome: 'Bruno Teste', crmNumero: '004567', crmUf: 'SP', especialidadeIds: ['e1'], ativo: false },
];
const json = (data, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } });

beforeEach(() => {
  sessionStorage.clear();
  entrarComo();
});

describe('catálogos para agendamento', () => {
  it('consulta os endpoints reais com a sessão e retorna somente ativos, preservando IDs e CRM', async () => {
    const fetchMock = vi.fn(async (url) => json(url.endsWith('/especialidades') ? especialidades : medicos));
    vi.stubGlobal('fetch', fetchMock);
    const result = await buscarCatalogosAgendamento();
    expect(result).toEqual({ especialidades: [especialidades[0]], medicos: [medicos[0]] });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    for (const path of ['/medicos', '/especialidades']) {
      expect(fetchMock).toHaveBeenCalledWith(`http://api.teste${path}`, expect.objectContaining({
        method: 'GET', headers: expect.objectContaining({ Authorization: 'Bearer token-de-teste' }),
      }));
    }
  });

  it('retorna listas vazias quando não há cadastros ativos', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json([])));
    await expect(buscarCatalogosAgendamento()).resolves.toEqual({ especialidades: [], medicos: [] });
  });

  it('propaga erro de API em vez de substituí-lo por listas vazias', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ erro: 'Serviço indisponível.' }, 503)));
    await expect(buscarCatalogosAgendamento()).rejects.toMatchObject({ status: 503, message: 'Serviço indisponível.' });
  });

  it('recusa resposta fora do contrato dos catálogos', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url) => json(url.endsWith('/especialidades') ? {} : medicos)));
    await expect(buscarCatalogosAgendamento()).rejects.toThrow('Não foi possível ler as especialidades e os médicos.');
  });

  it('cancela as duas consultas quando o chamador aborta', async () => {
    const signals = [];
    vi.stubGlobal('fetch', vi.fn((_url, { signal }) => {
      signals.push(signal);
      return new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }));
    }));
    const controller = new AbortController();
    const request = buscarCatalogosAgendamento({ signal: controller.signal });
    controller.abort();
    await expect(request).rejects.toMatchObject({ name: 'AbortError' });
    expect(signals).toHaveLength(2);
    expect(signals.every((signal) => signal.aborted)).toBe(true);
  });
});
