import { beforeEach, describe, expect, it, vi } from 'vitest';
import { buscarPacientesAgendamento } from '../services/pacienteAgendamentoService';
import { entrarComo } from './sessaoTeste';

const paciente = { id: 'p1', nome: 'Maria Teste', cpf: '01234567890', email: 'maria@example.com' };
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

beforeEach(() => {
  sessionStorage.clear();
  entrarComo();
});

describe('busca de pacientes para agendamento', () => {
  it('usa a busca do endpoint existente com a sessão e mantém CPF e ID como textos', async () => {
    const fetchMock = vi.fn(async () => json([paciente]));
    vi.stubGlobal('fetch', fetchMock);
    await expect(buscarPacientesAgendamento('  012.345.678-90  ')).resolves.toEqual([paciente]);
    const [url, init] = fetchMock.mock.calls[0];
    expect(new URL(url).pathname).toBe('/pacientes');
    expect(new URL(url).searchParams.get('busca')).toBe('012.345.678-90');
    expect(init).toMatchObject({ method: 'GET', headers: { Authorization: 'Bearer token-de-teste' } });
  });

  it('distingue lista vazia de erro HTTP', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json([])));
    await expect(buscarPacientesAgendamento()).resolves.toEqual([]);
    vi.stubGlobal('fetch', vi.fn(async () => json({ mensagem: 'Acesso negado.' }, 403)));
    await expect(buscarPacientesAgendamento()).rejects.toMatchObject({ status: 403, message: 'Acesso negado.' });
  });

  it('recusa resposta fora do contrato de listagem', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ mensagem: 'Resposta inesperada.' })));
    await expect(buscarPacientesAgendamento()).rejects.toThrow('Não foi possível ler os pacientes.');
  });
});
