import { beforeEach, describe, expect, it, vi } from 'vitest';
import { buscarVagas, confirmarAgendamento } from '../services/agendamentoService';
import { entrarComo } from './sessaoTeste';

const json = (data, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } });

beforeEach(() => {
  sessionStorage.clear();
  entrarComo();
});

describe('serviço de agendamento (#21)', () => {
  it('busca as vagas em GET /vagas com médico, especialidade e data', async () => {
    const vagas = { medicoId: 'm1', fusoHorario: 'America/Sao_Paulo', semAgenda: false,
      horarios: [{ inicio: '2026-12-01T11:00:00Z', fim: '2026-12-01T11:30:00Z' }] };
    const fetchMock = vi.fn(async () => json(vagas));
    vi.stubGlobal('fetch', fetchMock);

    await expect(buscarVagas({ especialidadeId: 'e1', medicoId: 'm1', data: '2026-12-01' })).resolves.toEqual(vagas);
    expect(fetchMock).toHaveBeenCalledWith(
      'http://api.teste/vagas?especialidadeId=e1&medicoId=m1&data=2026-12-01',
      expect.objectContaining({ method: 'GET', headers: expect.objectContaining({ Authorization: 'Bearer token-de-teste' }) }),
    );
  });

  it('confirma o agendamento em POST /consultas com os dados escolhidos', async () => {
    const fetchMock = vi.fn(async () => json({ id: 'c1', status: 'AGENDADA' }, 201));
    vi.stubGlobal('fetch', fetchMock);
    const dados = { pacienteId: 'p1', especialidadeId: 'e1', medicoId: 'm1', inicio: '2026-12-01T11:00:00Z', fim: '2026-12-01T11:30:00Z' };

    await expect(confirmarAgendamento(dados)).resolves.toMatchObject({ id: 'c1' });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('http://api.teste/consultas');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body)).toEqual(dados);
  });

  it('repassa o 409 de horário indisponível para a tela atualizar a lista', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ status: 409, erro: 'Este horário não está mais disponível.' }, 409)));
    await expect(confirmarAgendamento({})).rejects.toMatchObject({ status: 409, message: 'Este horário não está mais disponível.' });
  });
});
