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
