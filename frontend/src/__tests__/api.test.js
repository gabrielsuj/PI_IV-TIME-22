import { describe, expect, it, vi } from 'vitest';
import { api, API_BASE_URL, ApiError, buildUrl, request } from '../services/api';
import { getHealth } from '../services/healthService';

function jsonResponse(body, init = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
}

function mockFetch(implementation) {
  const fetchMock = vi.fn(implementation);
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('buildUrl', () => {
  it('usa a URL base definida em VITE_API_BASE_URL', () => {
    expect(API_BASE_URL).toBe('http://api.teste');
    expect(buildUrl('/health')).toBe('http://api.teste/health');
  });

  it('aceita caminho sem barra inicial e ignora parâmetros vazios', () => {
    expect(buildUrl('medicos', { especialidade: 'cardio', pagina: 2, filtro: undefined })).toBe(
      'http://api.teste/medicos?especialidade=cardio&pagina=2',
    );
  });
});

describe('request', () => {
  it('faz GET com cabeçalho Accept e devolve o JSON da resposta', async () => {
    const fetchMock = mockFetch(async () => jsonResponse({ status: 'ok' }));

    await expect(api.get('/health')).resolves.toEqual({ status: 'ok' });

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('http://api.teste/health');
    expect(init.method).toBe('GET');
    expect(init.headers.Accept).toBe('application/json');
    expect(init.body).toBeUndefined();
  });

  it('serializa o corpo em JSON nas requisições com body', async () => {
    const fetchMock = mockFetch(async () => jsonResponse({ id: '1' }, { status: 201 }));

    await api.post('/pacientes', { nome: 'Ana' });

    const [, init] = fetchMock.mock.calls[0];
    expect(init.method).toBe('POST');
    expect(init.headers['Content-Type']).toBe('application/json');
    expect(init.body).toBe('{"nome":"Ana"}');
  });

  it('devolve null em respostas sem conteúdo', async () => {
    mockFetch(async () => new Response(null, { status: 204 }));
    await expect(api.delete('/consultas/1')).resolves.toBeNull();
  });

  it('devolve texto quando a resposta não é JSON', async () => {
    mockFetch(async () => new Response('OK', { headers: { 'Content-Type': 'text/plain' } }));
    await expect(getHealth()).resolves.toBe('OK');
  });

  it('converte respostas de erro em ApiError com a mensagem da API', async () => {
    mockFetch(async () => jsonResponse({ message: 'Horário já reservado.' }, { status: 409 }));

    const error = await api.post('/consultas', {}).catch((e) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(409);
    expect(error.message).toBe('Horário já reservado.');
    expect(error.data).toEqual({ message: 'Horário já reservado.' });
    expect(error.isNetworkError).toBe(false);
  });

  it('usa mensagem genérica quando o erro não traz corpo', async () => {
    mockFetch(async () => new Response(null, { status: 500 }));

    const error = await api.get('/health').catch((e) => e);

    expect(error.status).toBe(500);
    expect(error.message).toBe('A API respondeu com erro (HTTP 500).');
  });

  it('converte falha de rede em ApiError com status 0', async () => {
    mockFetch(async () => {
      throw new TypeError('Failed to fetch');
    });

    const error = await getHealth().catch((e) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(0);
    expect(error.isNetworkError).toBe(true);
    expect(error.message).toMatch(/Não foi possível conectar à API/);
  });

  it('interrompe a requisição quando o tempo limite é atingido', async () => {
    mockFetch(
      (_url, { signal }) =>
        new Promise((_resolve, reject) => {
          signal.addEventListener('abort', () => reject(signal.reason));
        }),
    );

    const error = await request('/health', { timeout: 20 }).catch((e) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(0);
    expect(error.message).toMatch(/demorou para responder/);
  });

  it('propaga o cancelamento feito pelo chamador sem convertê-lo', async () => {
    mockFetch(
      (_url, { signal }) =>
        new Promise((_resolve, reject) => {
          signal.addEventListener('abort', () => reject(signal.reason));
        }),
    );
    const controller = new AbortController();

    const pending = api.get('/health', { signal: controller.signal }).catch((e) => e);
    controller.abort();
    const error = await pending;

    expect(error).not.toBeInstanceOf(ApiError);
    expect(error.name).toBe('AbortError');
  });
});
