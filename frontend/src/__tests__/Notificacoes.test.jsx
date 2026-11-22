/*
Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { expect, it, vi } from 'vitest';
import { routes } from '../routes';
import { entrarComo } from './sessaoTeste';

it('marcar como lida atualiza o indicador e mantém a opção de confirmar presença', async () => {
  entrarComo({ id: 'u1', nome: 'Paulo', perfil: 'PACIENTE' });
  const n = { id: 'id-lembrete', titulo: 'Lembrete de consulta', mensagem: 'Confirme sua presença.',
    medicoNome: 'Dra. Ana', dataHora: '2099-11-20T13:00:00Z', lida: false, podeConfirmar: true };
  vi.stubGlobal('fetch', vi.fn(async (url, init) => {
    const path = new URL(url).pathname;
    if (init.method === 'POST') {
      expect(JSON.parse(init.body)).toEqual({ notificacaoId: n.id });
      if (path === '/notificacoes/lida') n.lida = true;
      else {
        expect(path).toBe('/notificacoes/confirmar');
        n.presencaConfirmada = true;
        n.podeConfirmar = false;
      }
      return new Response(null, { status: 204 });
    }
    expect(path).toBe('/notificacoes');
    return Response.json([n]);
  }));
  const user = userEvent.setup();
  render(<RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/notificacoes'] })} />);
  expect(await screen.findByText('Não lidas: 1')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Marcar como lida' }));
  expect(await screen.findByText('Não lidas: 0')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Confirmar presença' }));
  expect(await screen.findByText('Presença confirmada')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Confirmar presença' })).not.toBeInTheDocument();
});

// #38: lembrete e confirmação entregues antes do cancelamento continuam na central, marcados.
it('marca os avisos de uma consulta cancelada e não oferece confirmar presença', async () => {
  entrarComo({ id: 'u2', nome: 'Carla', perfil: 'PACIENTE' });
  const base = { medicoNome: 'Dra. Ana', dataHora: '2099-11-23T11:30:00Z', lida: false, podeConfirmar: false };
  vi.stubGlobal('fetch', vi.fn(async () => Response.json([
    { ...base, id: 'lembrete', titulo: 'Lembrete de consulta', mensagem: 'Confirme sua presença.', consultaCancelada: true },
    { ...base, id: 'agendada', titulo: 'Consulta agendada', mensagem: 'Sua consulta foi agendada.', consultaCancelada: true },
    { ...base, id: 'outra', titulo: 'Consulta agendada', mensagem: 'Outra consulta, ainda válida.', dataHora: '2099-11-30T11:00:00Z', consultaCancelada: false },
  ])));
  render(<RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/notificacoes'] })} />);

  await screen.findByText('Outra consulta, ainda válida.');
  expect(screen.getAllByText('Consulta cancelada')).toHaveLength(2);
  expect(screen.getAllByText('Esta consulta foi cancelada depois deste aviso.')).toHaveLength(2);
  const linhaValida = screen.getByText('Outra consulta, ainda válida.').closest('tr');
  expect(linhaValida).not.toHaveTextContent('Consulta cancelada');
  expect(screen.queryByRole('button', { name: 'Confirmar presença' })).not.toBeInTheDocument();
});
