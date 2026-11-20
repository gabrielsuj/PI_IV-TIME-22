import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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

it('preserva o aviso e aguarda a verificação da vaga após falha na confirmação', async () => {
  const verificacao = Promise.withResolvers();
  let envios = 0;
  const fetchMock = vi.fn(async (_url, init) => {
    if (init.method === 'POST') {
      envios++;
      return envios === 1 ? json({ erro: 'Serviço indisponível.' }, 503) : json({ id: 'consulta-1' }, 201);
    }
    if (envios === 1) return verificacao.promise;
    return json([envios === 2 ? { ...AVISO, status: 'AGENDADO', consultaAgendadaId: 'consulta-1' } : AVISO]);
  });
  abrirAvisos(fetchMock);
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Agendar vaga' }));
  const modal = screen.getByRole('dialog', { name: 'Confirmar agendamento' });
  const confirmar = within(modal).getByRole('button', { name: 'Confirmar agendamento' });
  await user.click(confirmar);

  expect(await within(modal).findByRole('alert')).toHaveTextContent('Serviço indisponível.');
  expect(modal).toHaveTextContent(AVISO.medicoNome);
  expect(confirmar).toBeDisabled();
  expect(within(modal).getByRole('status')).toHaveTextContent(/Verificando a disponibilidade/);

  verificacao.resolve(json([AVISO]));
  await waitFor(() => expect(confirmar).toBeEnabled());
  await user.click(confirmar);

  expect(await screen.findByText('Consulta agendada com sucesso.')).toBeInTheDocument();
  expect(fetchMock.mock.calls.filter(([, init]) => init.method === 'POST').map(([, init]) => JSON.parse(init.body)))
    .toEqual([{ avisoId: AVISO.id }, { avisoId: AVISO.id }]);
});

it('mostra a consulta recuperada quando a confirmação foi registrada antes de cair a conexão', async () => {
  let agendado = false;
  const fetchMock = vi.fn(async (_url, init) => {
    if (init.method === 'POST') {
      agendado = true;
      throw new TypeError('Conexão interrompida após a gravação');
    }
    return json([agendado ? { ...AVISO, status: 'AGENDADO', consultaAgendadaId: 'consulta-1' } : AVISO]);
  });
  abrirAvisos(fetchMock);
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Agendar vaga' }));
  const modal = within(screen.getByRole('dialog', { name: 'Confirmar agendamento' }));
  await user.click(modal.getByRole('button', { name: 'Confirmar agendamento' }));

  expect(await modal.findByRole('link', { name: 'Ver consulta' })).toHaveAttribute('href', '/consultas/consulta-1');
  expect(modal.getByText('A consulta deste aviso já foi agendada.')).toBeInTheDocument();
  expect(modal.getByRole('button', { name: 'Confirmar agendamento' })).toBeDisabled();
  expect(fetchMock.mock.calls.filter(([, init]) => init.method === 'POST')).toHaveLength(1);
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

it('recupera o cadastro de especialidades dentro do formulário da fila e mantém o turno preenchido', async () => {
  const idEspecialidade = '1'.repeat(24);
  const respostaEspecialidades = Promise.withResolvers();
  let consultasEspecialidades = 0;
  const fetchMock = vi.fn(async (url, init) => {
    const { pathname } = new URL(url);
    if (pathname === '/especialidades') {
      consultasEspecialidades++;
      return consultasEspecialidades === 1
        ? json({ erro: 'Catálogo temporariamente indisponível.' }, 503)
        : respostaEspecialidades.promise;
    }
    if (pathname === '/fila-espera' && init.method === 'POST') return json({ id: 'fila-1' }, 201);
    return json([]);
  });
  abrirAvisos(fetchMock, '/fila-espera');
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Entrar na fila' }));
  const modal = within(screen.getByRole('dialog', { name: 'Entrar na fila' }));
  const confirmar = modal.getByRole('button', { name: 'Confirmar inscrição' });
  const turno = modal.getByLabelText('Turno preferido');

  await user.selectOptions(turno, 'TARDE');
  expect(await modal.findByRole('alert')).toHaveTextContent('Catálogo temporariamente indisponível.');
  expect(confirmar).toBeDisabled();
  await user.click(modal.getByRole('button', { name: 'Tentar novamente' }));
  expect(modal.getByRole('status')).toHaveTextContent('Carregando cadastros…');
  expect(turno).toHaveValue('TARDE');

  await act(async () => {
    respostaEspecialidades.resolve(json([{ id: idEspecialidade, nome: 'Cardiologia' }]));
  });
  await user.selectOptions(modal.getByRole('combobox', { name: 'Especialidade' }), idEspecialidade);
  await user.click(confirmar);
  expect(await screen.findByText('Inscrição realizada com sucesso.')).toBeInTheDocument();
  const [, envio] = fetchMock.mock.calls.find(([, init]) => init.method === 'POST');
  expect(JSON.parse(envio.body)).toMatchObject({
    especialidadeId: idEspecialidade, turnoPreferido: 'TARDE', medicoId: null,
  });
});
