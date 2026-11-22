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

// ---- #58: motivo da expiração e volta à busca de vagas ----

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
  expect(within(linha('Dra. Ocupada')).getByRole('link', { name: 'Buscar outra vaga' })).toHaveAttribute('href', '/nova-consulta');

  expect(within(linha('Dr. Prazo')).getByText('Prazo encerrado')).toBeInTheDocument();
  expect(within(linha('Dr. Prazo')).getByRole('link', { name: 'Ver fila de espera' })).toHaveAttribute('href', '/fila-espera');
  expect(within(linha('Dr. Prazo')).queryByRole('link', { name: 'Buscar outra vaga' })).not.toBeInTheDocument();

  expect(within(linha('Dr. Agenda')).getByText('Oferta desatualizada')).toBeInTheDocument();
  expect(within(linha('Dr. Agenda')).getByRole('link', { name: 'Buscar outra vaga' })).toBeInTheDocument();
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

it('explica a vaga ocupada depois do 409 e leva à busca filtrada pelo aviso', async () => {
  let ocupada = false;
  abrirAvisos(vi.fn(async (url, init) => {
    const { pathname } = new URL(url);
    if (pathname === '/especialidades') return json([{ id: ESPECIALIDADE_ID, nome: 'Cardiologia', ativa: true }]);
    if (pathname === '/medicos') {
      return json([{ id: MEDICO_ID, nome: 'Dra. Paula Lima', crmNumero: '1', crmUf: 'SP', especialidadeIds: [ESPECIALIDADE_ID], ativo: true }]);
    }
    if (init.method === 'POST') {
      ocupada = true;
      return json({ erro: 'A vaga deste aviso expirou ou já foi ocupada.' }, 409);
    }
    return json([{ ...AVISO, ...COM_FILTROS, ...(ocupada ? { status: 'EXPIRADO', motivo: 'VAGA_OCUPADA' } : {}) }]);
  }));
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Agendar vaga' }));
  await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirmar agendamento' }));

  const alerta = await screen.findByText(/Outro paciente confirmou este horário antes de você/);
  expect(alerta).toHaveTextContent(/continua na fila/);
  expect(await screen.findByText('Vaga ocupada')).toBeInTheDocument();

  const buscar = screen.getAllByRole('link', { name: 'Buscar outra vaga' })[0];
  await user.click(buscar);

  expect(await screen.findByRole('heading', { name: 'Nova consulta' })).toBeInTheDocument();
  expect(screen.getByText(/Buscando outra vaga com o médico e a especialidade do aviso/)).toBeInTheDocument();
  await waitFor(() => expect(screen.getByRole('combobox', { name: /Especialidade/ })).toHaveValue(ESPECIALIDADE_ID));
  await waitFor(() => expect(screen.getByRole('combobox', { name: /Médico/ })).toHaveValue(MEDICO_ID));
});

it('mostra a mensagem do servidor quando o aviso recarregado não traz motivo', async () => {
  let recusado = false;
  abrirAvisos(vi.fn(async (_url, init) => {
    if (init.method === 'POST') {
      recusado = true;
      return json({ erro: 'Aviso de vaga não encontrado' }, 404);
    }
    return recusado ? json([]) : json([AVISO]);
  }));
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Agendar vaga' }));
  await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirmar agendamento' }));

  expect(await screen.findByText('Aviso de vaga não encontrado')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Buscar outra vaga' })).toHaveAttribute('href', '/nova-consulta');
});

it('explica no modal a oferta desatualizada pela agenda', async () => {
  abrirAvisos(vi.fn(async () => json([{ ...AVISO, ...COM_FILTROS, status: 'EXPIRADO', motivo: 'OPORTUNIDADE_DESATUALIZADA' }])),
    { pathname: '/avisos-vaga', state: { avisoId: AVISO.id } });

  const modal = await screen.findByRole('dialog', { name: 'Confirmar agendamento' });
  expect(await within(modal).findByText(/A agenda do médico mudou/)).toBeInTheDocument();
  expect(within(modal).getByRole('link', { name: 'Buscar outra vaga' })).toBeInTheDocument();
  expect(within(modal).getByRole('button', { name: 'Confirmar agendamento' })).toBeDisabled();
});

it('no prazo encerrado o modal leva de volta à fila, e não à busca', async () => {
  abrirAvisos(vi.fn(async () => json([{ ...AVISO, status: 'EXPIRADO', motivo: 'PRAZO_ENCERRADO' }])),
    { pathname: '/avisos-vaga', state: { avisoId: AVISO.id } });

  const modal = await screen.findByRole('dialog', { name: 'Confirmar agendamento' });
  expect(await within(modal).findByText(/O prazo deste aviso terminou/)).toBeInTheDocument();
  expect(within(modal).getByRole('link', { name: 'Ver fila de espera' })).toHaveAttribute('href', '/fila-espera');
  expect(within(modal).queryByRole('link', { name: 'Buscar outra vaga' })).not.toBeInTheDocument();
});

it('sem avisos, orienta o paciente a entrar na fila de espera', async () => {
  abrirAvisos(vi.fn(async () => json([])));

  expect(await screen.findByText(/Você ainda não recebeu avisos de vaga/)).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'fila de espera' })).toHaveAttribute('href', '/fila-espera');
});
