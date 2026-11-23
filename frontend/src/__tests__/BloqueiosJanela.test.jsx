import { render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import BloqueiosCard from '../components/agenda/BloqueiosCard';
import { buscarAgenda, listarBloqueios } from '../services/agendaService';

vi.mock('../services/agendaService', () => ({
  buscarAgenda: vi.fn(), listarBloqueios: vi.fn(), criarBloqueio: vi.fn(),
  removerBloqueio: vi.fn(), conflitoComConsultas: vi.fn(),
}));

beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2028-03-01T01:00:00Z'));
  listarBloqueios.mockResolvedValue([]);
});
afterEach(() => vi.useRealTimers());

it('consulta o período no fuso da agenda selecionada', async () => {
  buscarAgenda.mockResolvedValue({ fusoHorario: 'Asia/Tokyo' });
  render(<BloqueiosCard medicoId="m1" />);
  await waitFor(() => expect(listarBloqueios).toHaveBeenCalledWith('m1', expect.objectContaining({
    de: '2028-03-01', ate: '2029-03-01',
  })));
});

it('sem agenda usa São Paulo e ajusta o fim de fevereiro', async () => {
  buscarAgenda.mockResolvedValue(null);
  render(<BloqueiosCard medicoId="m2" />);
  await waitFor(() => expect(listarBloqueios).toHaveBeenCalledWith('m2', expect.objectContaining({
    de: '2028-02-29', ate: '2029-02-28',
  })));
});
