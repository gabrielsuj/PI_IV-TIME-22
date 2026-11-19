import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { routes } from '../routes';
import { entrarComo } from './sessaoTeste';

const PACIENTE = { id: 'u-paciente', nome: 'Paulo Paciente', perfil: 'PACIENTE' };
const AVISO = {
  id: 'aviso-1', medicoNome: 'Dra. Paula Lima', inicio: '2026-11-24T13:00:00Z',
  fim: '2026-11-24T13:30:00Z', validoAte: '2026-11-24T13:00:00Z', status: 'DISPONIVEL',
};
const json = (body, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' },
});

function abrirAvisos(fetchMock, rota = '/avisos-vaga') {
  entrarComo(PACIENTE);
  vi.stubGlobal('fetch', fetchMock);
  render(<RouterProvider router={createMemoryRouter(routes, { initialEntries: [rota] })} />);
}

beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value() { this.open = true; } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value() { this.open = false; } });
});

it('confirma usando o ID do aviso e mostra o link da consulta marcada', async () => {
  let agendado = false;
  const fetchMock = vi.fn(async (url, init) => {
    expect(new URL(url).pathname).toBe('/avisos-vaga');
    if (init.method === 'POST') {
      expect(JSON.parse(init.body)).toEqual({ avisoId: AVISO.id });
      agendado = true;
      return json({ id: 'consulta-1' }, 201);
    }
    return json([agendado ? { ...AVISO, status: 'AGENDADO', consultaAgendadaId: 'consulta-1' } : AVISO]);
  });
  const user = userEvent.setup();
  abrirAvisos(fetchMock);

  await user.click(await screen.findByRole('button', { name: 'Agendar vaga' }));
  const modal = screen.getByRole('dialog', { name: 'Confirmar agendamento' });
  expect(modal).toHaveTextContent('Dra. Paula Lima');
  await user.click(within(modal).getByRole('button', { name: 'Confirmar agendamento' }));

  expect(await screen.findByText('Consulta agendada com sucesso.')).toBeInTheDocument();
  expect(await screen.findByRole('link', { name: 'Ver consulta', exact: true })).toHaveAttribute('href', '/consultas/consulta-1');
  expect(screen.queryByRole('button', { name: 'Agendar vaga' })).not.toBeInTheDocument();
});

it('mostra como expirado o aviso que perdeu a disputa pelo horário', async () => {
  let ocupada = false;
  abrirAvisos(vi.fn(async (_url, init) => {
    if (init.method === 'POST') {
      ocupada = true;
      return json({ erro: 'A vaga deste aviso expirou ou já foi ocupada.' }, 409);
    }
    return json([{ ...AVISO, status: ocupada ? 'EXPIRADO' : 'DISPONIVEL' }]);
  }));
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Agendar vaga' }));
  await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirmar agendamento' }));

  expect(await screen.findByRole('alert')).toHaveTextContent('A vaga deste aviso expirou ou já foi ocupada.');
  expect(await screen.findByText('Expirado')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Agendar vaga' })).not.toBeInTheDocument();
});

it('atualiza o aviso ao retornar à tela depois de outro paciente agendar', async () => {
  let status = 'DISPONIVEL';
  abrirAvisos(vi.fn(async () => json([{ ...AVISO, status }])));
  expect(await screen.findByText('Disponível')).toBeInTheDocument();

  status = 'EXPIRADO';
  fireEvent.focus(window);

  expect(await screen.findByText('Expirado')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Agendar vaga' })).not.toBeInTheDocument();
});

it('abre pela inscrição o aviso certo e revalida a expiração antes de confirmar', async () => {
  const inscricao = { id: 'fila-1', status: 'SINALIZADA', ultimaSinalizacao: { notificacaoId: AVISO.id } };
  abrirAvisos(vi.fn(async (url) => {
    const { pathname, searchParams } = new URL(url);
    if (pathname === '/fila-espera') return json(searchParams.has('status') ? [] : [inscricao]);
    if (pathname === '/avisos-vaga') return json([{ ...AVISO, status: 'EXPIRADO' }]);
    return json([]);
  }), '/fila-espera');
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Agendar vaga' }));
  const modal = await screen.findByRole('dialog', { name: 'Confirmar agendamento' });
  expect(modal).toHaveTextContent('A vaga deste aviso não está mais disponível.');
  expect(within(modal).getByRole('button', { name: 'Confirmar agendamento' })).toBeDisabled();
  await user.click(within(modal).getByRole('button', { name: 'Voltar' }));
  expect(screen.getByRole('link', { name: 'Buscar outra vaga' })).toHaveAttribute('href', '/nova-consulta');
});
