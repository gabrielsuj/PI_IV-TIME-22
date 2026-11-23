/*
Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

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

it('atualiza o aviso ao retornar à tela depois de a secretaria agendar', async () => {
  let status = 'DISPONIVEL';
  abrirAvisos(vi.fn(async () => json([{ ...AVISO, status }])));
  expect(await screen.findByText('Disponível')).toBeInTheDocument();

  status = 'EXPIRADO';
  fireEvent.focus(window);

  expect(await screen.findByText('Expirado')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Agendar vaga' })).not.toBeInTheDocument();
});

it('abre o aviso da inscrição sem oferecer confirmação', async () => {
  const inscricao = { id: 'fila-1', status: 'SINALIZADA', ultimaSinalizacao: { notificacaoId: AVISO.id } };
  const fetchMock = vi.fn(async (url) => {
    const { pathname, searchParams } = new URL(url);
    if (pathname === '/fila-espera') return json(searchParams.has('status') ? [] : [inscricao]);
    if (pathname === '/avisos-vaga') return json([AVISO]);
    return json([]);
  });
  abrirAvisos(fetchMock, '/fila-espera');
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Ver aviso de vaga' }));
  expect(await screen.findByText(AVISO.medicoNome)).toBeInTheDocument();
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /Agendar vaga|Confirmar agendamento/ })).not.toBeInTheDocument();
  expect(fetchMock.mock.calls.every(([, init]) => init.method === 'GET')).toBe(true);
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

// Motivos da expiração continuam disponíveis na leitura dos avisos.

const MEDICO_ID = 'a'.repeat(24);
const ESPECIALIDADE_ID = 'b'.repeat(24);
const COM_FILTROS = { medicoId: MEDICO_ID, especialidadeId: ESPECIALIDADE_ID };

it('mostra na lista por que cada aviso expirou e a saída certa para cada caso', async () => {
  abrirAvisos(vi.fn(async () => json([
    { ...AVISO, ...COM_FILTROS, id: 'ocupado', status: 'EXPIRADO', motivo: 'VAGA_OCUPADA', medicoNome: 'Dra. Ocupada' },
    { ...AVISO, ...COM_FILTROS, id: 'prazo', status: 'EXPIRADO', motivo: 'PRAZO_ENCERRADO', medicoNome: 'Dr. Prazo' },
    { ...AVISO, ...COM_FILTROS, id: 'agenda', status: 'EXPIRADO', motivo: 'OPORTUNIDADE_DESATUALIZADA', medicoNome: 'Dr. Agenda' },
  ])));

  const linha = (nome) => screen.getByRole('cell', { name: nome }).closest('tr');
  await screen.findByText('Dra. Ocupada');

  expect(within(linha('Dra. Ocupada')).getByText('Vaga ocupada')).toBeInTheDocument();
  expect(within(linha('Dra. Ocupada')).getByRole('link', { name: 'Ver fila de espera' })).toHaveAttribute('href', '/fila-espera');

  expect(within(linha('Dr. Prazo')).getByText('Prazo encerrado')).toBeInTheDocument();
  expect(within(linha('Dr. Prazo')).getByRole('link', { name: 'Ver fila de espera' })).toHaveAttribute('href', '/fila-espera');
  expect(within(linha('Dr. Prazo')).queryByRole('link', { name: 'Buscar outra vaga' })).not.toBeInTheDocument();

  expect(within(linha('Dr. Agenda')).getByText('Oferta desatualizada')).toBeInTheDocument();
  expect(within(linha('Dr. Agenda')).getByRole('link', { name: 'Ver fila de espera' })).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: 'Buscar outra vaga' })).not.toBeInTheDocument();
});

it('não mostra motivo para aviso disponível ou agendado', async () => {
  abrirAvisos(vi.fn(async () => json([
    { ...AVISO, id: 'livre', motivo: null },
    { ...AVISO, id: 'feito', status: 'AGENDADO', consultaAgendadaId: 'consulta-9', medicoNome: 'Dr. Feito' },
  ])));
  await screen.findByText('Dr. Feito');

  for (const rotulo of ['Vaga ocupada', 'Prazo encerrado', 'Oferta desatualizada', 'Indisponível']) {
    expect(screen.queryByText(rotulo)).not.toBeInTheDocument();
  }
});

it('sem avisos, orienta o paciente a entrar na fila de espera', async () => {
  abrirAvisos(vi.fn(async () => json([])));

  expect(await screen.findByText(/Você ainda não recebeu avisos de vaga/)).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'fila de espera' })).toHaveAttribute('href', '/fila-espera');
});

it('aviso disponível orienta a procurar a secretaria e só realiza leituras', async () => {
  const fetchMock = vi.fn(async () => json([AVISO]));
  abrirAvisos(fetchMock, { pathname: '/avisos-vaga', state: { avisoId: AVISO.id } });
  const user = userEvent.setup();
  await screen.findByText(AVISO.medicoNome);
  expect(screen.getByText(/Para agendar, entre em contato com a secretaria/)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /Agendar vaga|Confirmar agendamento/ })).not.toBeInTheDocument();
  expect(screen.queryByRole('link', { name: /Nova consulta|Buscar outra vaga/ })).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Atualizar avisos' }));
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
  expect(fetchMock.mock.calls.every(([, init]) => init.method === 'GET')).toBe(true);
});

it('mantém o acesso à consulta agendada pela secretaria', async () => {
  abrirAvisos(vi.fn(async () => json([{ ...AVISO, status: 'AGENDADO', consultaAgendadaId: 'c1' }])));
  expect(await screen.findByRole('link', { name: 'Ver consulta' })).toHaveAttribute('href', '/consultas/c1');
});
