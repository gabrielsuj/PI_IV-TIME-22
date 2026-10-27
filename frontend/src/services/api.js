/**
 * Cliente HTTP centralizado da aplicação.
 *
 * Toda comunicação com a API REST passa por aqui: a URL base vem da variável
 * de ambiente VITE_API_BASE_URL, os corpos são enviados e lidos em JSON e
 * qualquer falha (resposta 4xx/5xx, rede indisponível ou tempo esgotado) é
 * convertida em ApiError, para que as telas tratem erros de forma uniforme.
 */

const FALLBACK_BASE_URL = 'http://localhost:8080';
const DEFAULT_TIMEOUT_MS = 10_000;

function resolveBaseUrl(value) {
  if (!value) {
    console.warn(
      `[api] VITE_API_BASE_URL não definida; usando ${FALLBACK_BASE_URL}. ` +
        'Configure a variável no arquivo .env da raiz do repositório.',
    );
    return FALLBACK_BASE_URL;
  }
  return value.replace(/\/+$/, '');
}

export const API_BASE_URL = resolveBaseUrl(import.meta.env.VITE_API_BASE_URL);

export class ApiError extends Error {
  /**
   * @param {string} message mensagem pronta para exibição
   * @param {object} details
   * @param {number} details.status código HTTP; 0 quando não houve resposta
   * @param {unknown} [details.data] corpo da resposta de erro, se houver
   * @param {unknown} [details.cause] erro original
   */
  constructor(message, { status, data = null, cause } = {}) {
    super(message, { cause });
    this.name = 'ApiError';
    this.status = status;
    this.data = data;
  }

  /** Verdadeiro quando a API não respondeu (fora do ar, sem rede ou timeout). */
  get isNetworkError() {
    return this.status === 0;
  }
}

export function buildUrl(path, params) {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`;
  const url = new URL(API_BASE_URL + normalizedPath);
  if (params) {
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) {
        url.searchParams.set(key, value);
      }
    }
  }
  return url.toString();
}

async function parseBody(response) {
  if (response.status === 204) return null;
  const text = await response.text();
  if (!text) return null;
  const contentType = response.headers.get('Content-Type') ?? '';
  if (contentType.includes('application/json')) {
    try {
      return JSON.parse(text);
    } catch {
      return text;
    }
  }
  return text;
}

function errorMessageFrom(data, status) {
  if (data && typeof data === 'object') {
    if (typeof data.message === 'string') return data.message;
    if (typeof data.error === 'string') return data.error;
    if (typeof data.error?.message === 'string') return data.error.message;
  }
  return `A API respondeu com erro (HTTP ${status}).`;
}

/**
 * Executa uma requisição à API.
 *
 * @param {string} path caminho relativo à URL base, por exemplo "/health"
 * @param {object} [options]
 * @param {string} [options.method="GET"]
 * @param {unknown} [options.body] objeto serializado como JSON
 * @param {Record<string, string|number|boolean>} [options.params] query string
 * @param {Record<string, string>} [options.headers]
 * @param {AbortSignal} [options.signal] permite cancelar a requisição (ex.: ao desmontar a tela)
 * @param {number} [options.timeout] tempo máximo em milissegundos
 * @returns {Promise<any>} corpo da resposta já convertido
 * @throws {ApiError} em respostas de erro, falha de rede ou timeout.
 *   Cancelamentos feitos pelo próprio chamador (signal) propagam o AbortError original.
 */
export async function request(
  path,
  { method = 'GET', body, params, headers, signal, timeout = DEFAULT_TIMEOUT_MS } = {},
) {
  const timeoutSignal = AbortSignal.timeout(timeout);
  const finalHeaders = { Accept: 'application/json', ...headers };
  const init = {
    method,
    headers: finalHeaders,
    signal: signal ? AbortSignal.any([signal, timeoutSignal]) : timeoutSignal,
  };
  if (body !== undefined) {
    finalHeaders['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }

  let response;
  try {
    response = await fetch(buildUrl(path, params), init);
  } catch (error) {
    if (signal?.aborted) throw error;
    if (timeoutSignal.aborted) {
      throw new ApiError('A API demorou para responder. Tente novamente.', {
        status: 0,
        cause: error,
      });
    }
    throw new ApiError('Não foi possível conectar à API. Verifique se o servidor está em execução.', {
      status: 0,
      cause: error,
    });
  }

  const data = await parseBody(response);
  if (!response.ok) {
    throw new ApiError(errorMessageFrom(data, response.status), {
      status: response.status,
      data,
    });
  }
  return data;
}

export const api = {
  get: (path, options) => request(path, { ...options, method: 'GET' }),
  post: (path, body, options) => request(path, { ...options, method: 'POST', body }),
  put: (path, body, options) => request(path, { ...options, method: 'PUT', body }),
  patch: (path, body, options) => request(path, { ...options, method: 'PATCH', body }),
  delete: (path, options) => request(path, { ...options, method: 'DELETE' }),
};
