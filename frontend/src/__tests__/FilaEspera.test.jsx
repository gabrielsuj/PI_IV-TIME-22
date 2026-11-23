/*
Autoria: Murilo Moraes
RA: 25000073
*/
/**
 * UX da fila de espera (#58): vaga perdida, saída da fila e estados vazios.
 */
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { routes } from '../routes';
import { entrarComo, SECRETARIA_TESTE } from './sessaoTeste';

const PACIENTE = { id: 'u-paciente', nome: 'Paulo Paciente', perfil: 'PACIENTE' };
const MEDICO_ID = 'a'.repeat(24);
const ESPECIALIDADE_ID = 'b'.repeat(24);
const ESPECIALIDADES = [{ id: ESPECIALIDADE_ID, nome: 'Cardiologia', ativa: true }];
const MEDICOS = [{
  id: MEDICO_ID, nome: 'Dra. Paula Lima', crmNumero: '123', crmUf: 'SP', especialidadeIds: [ESPECIALIDADE_ID], ativo: true,
}];

const AGUARDANDO = {
  id: 'fila-1', pacienteId: 'p1', pacienteNome: 'Paulo Paciente', especialidadeId: ESPECIALIDADE_ID,
  especialidadeNome: 'Cardiologia', medicoId: null, medicoNome: null, turnoPreferido: 'QUALQUER',
  criadoEm: '2026-11-20T12:00:00Z', status: 'AGUARDANDO',
};
const SINALIZADA = {
  ...AGUARDANDO, status: 'SINALIZADA',
  ultimaSinalizacao: {
    notificacaoId: 'aviso-1', consultaOrigemId: 'c1', medicoId: MEDICO_ID,
    medicoNome: 'Dra. Paula Lima', dataHora: '2026-11-24T13:00:00Z',
  },
};

const json = (body, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' },
});

/**
 * Simula a API da fila. `ativas` pode ser uma lista ou uma função, para mudar a resposta
 * entre uma leitura e outra; `aoCancelar` decide a resposta do DELETE.
 */
function apiDaFila({ ativas = [], atendidas = [], aoCancelar = () => json({ ...AGUARDANDO, status: 'CANCELADA' }) } = {}) {
  return vi.fn(async (url, init = {}) => {
    const { pathname, searchParams } = new URL(url);
    if (pathname === '/especialidades') return json(ESPECIALIDADES);
    if (pathname === '/medicos') return json(MEDICOS);
    if (pathname === '/pacientes') return json([{ id: 'p1', nome: 'Paulo Paciente' }]);
    if (pathname.startsWith('/fila-espera/') && init.method === 'DELETE') return aoCancelar();
    if (pathname === '/fila-espera') {
      if (searchParams.get('status') === 'ATENDIDA') return json(atendidas);
      return json(typeof ativas === 'function' ? ativas() : ativas);
    }
    return json([]);
  });
}

function abrirFila(fetchMock, usuario = PACIENTE) {
  entrarComo(usuario);
  vi.stubGlobal('fetch', fetchMock);
  render(<RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/fila-espera'] })} />);
}

const pedidosDelete = (fetchMock) => fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE');

beforeEach(() => {
  sessionStorage.clear();
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value() { this.open = true; } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value() { this.open = false; } });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('estados vazios da fila', () => {
  it('orienta o paciente sem inscrição a entrar na fila', async () => {
    abrirFila(apiDaFila());

    expect(await screen.findByText(/Você não está em nenhuma fila de espera/)).toBeInTheDocument();
    expect(screen.getByText(/para ser avisado quando um horário compatível for liberado/)).toBeInTheDocument();
  });

  it('orienta a secretaria a inscrever um paciente', async () => {
    abrirFila(apiDaFila(), SECRETARIA_TESTE);

    expect(await screen.findByText(/Nenhuma inscrição ativa na fila de espera/)).toBeInTheDocument();
    expect(screen.getByText(/para inscrever um paciente/)).toBeInTheDocument();
  });

  it('explica a lista de atendidas vazia ao trocar o filtro', async () => {
    const user = userEvent.setup();
    abrirFila(apiDaFila());
    await screen.findByText(/Você não está em nenhuma fila de espera/);

    await user.selectOptions(screen.getByLabelText('Visualizar inscrições'), 'ATENDIDA');

    expect(await screen.findByText(/Nenhuma inscrição atendida ainda/)).toBeInTheDocument();
  });

  it('a nota explica a atualização automática em vez de detalhes do backend', async () => {
    abrirFila(apiDaFila());
    await screen.findByText(/Você não está em nenhuma fila de espera/);

    expect(screen.getByText(/atualizada automaticamente a cada 30 segundos/)).toBeInTheDocument();
    expect(screen.queryByText(/contrato atual do backend/)).not.toBeInTheDocument();
  });
});

describe('saída da fila', () => {
  async function pedirSaida(fetchMock, usuario = PACIENTE) {
    const user = userEvent.setup();
    abrirFila(fetchMock, usuario);
    await user.click(await screen.findByRole('button', { name: 'Cancelar inscrição de Paulo Paciente' }));
    return { user, modal: screen.getByRole('dialog', { name: 'Sair da fila de espera' }) };
  }

  it('pede confirmação em um modal, sem usar o diálogo do navegador', async () => {
    const confirmar = vi.spyOn(window, 'confirm');
    const { modal } = await pedirSaida(apiDaFila({ ativas: [AGUARDANDO] }));

    expect(confirmar).not.toHaveBeenCalled();
    expect(modal).toHaveTextContent('Paulo Paciente · Cardiologia · Qualquer médico');
    expect(modal).toHaveTextContent(/deixa de receber avisos/);
  });

  it('mantém a inscrição quando o paciente desiste', async () => {
    const fetchMock = apiDaFila({ ativas: [AGUARDANDO] });
    const { user, modal } = await pedirSaida(fetchMock);

    await user.click(within(modal).getByRole('button', { name: 'Manter inscrição' }));

    expect(screen.queryByRole('dialog', { name: 'Sair da fila de espera' })).not.toBeInTheDocument();
    expect(pedidosDelete(fetchMock)).toHaveLength(0);
  });

  it('cancela a inscrição e recarrega a lista', async () => {
    let cancelada = false;
    const fetchMock = apiDaFila({
      ativas: () => (cancelada ? [] : [AGUARDANDO]),
      aoCancelar: () => { cancelada = true; return json({ ...AGUARDANDO, status: 'CANCELADA' }); },
    });
    const { user, modal } = await pedirSaida(fetchMock);

    await user.click(within(modal).getByRole('button', { name: 'Sair da fila' }));

    expect(await screen.findByText('Inscrição cancelada com sucesso.')).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: 'Sair da fila de espera' })).not.toBeInTheDocument();
    expect(new URL(pedidosDelete(fetchMock)[0][0]).pathname).toBe('/fila-espera/fila-1');
    expect(await screen.findByText(/Você não está em nenhuma fila de espera/)).toBeInTheDocument();
  });

  it('avisa que a vaga oferecida fica livre ao sair de uma inscrição sinalizada', async () => {
    const { modal } = await pedirSaida(apiDaFila({ ativas: [SINALIZADA] }), SECRETARIA_TESTE);

    expect(modal).toHaveTextContent(/tem uma vaga oferecida/);
    expect(modal).toHaveTextContent(/fica livre para o próximo paciente/);
  });

  it('explica a inscrição que já tinha sido encerrada em outro lugar', async () => {
    let encerrada = false;
    const fetchMock = apiDaFila({
      ativas: () => (encerrada ? [] : [AGUARDANDO]),
      aoCancelar: () => { encerrada = true; return json({ erro: 'Inscrição já encerrada' }, 409); },
    });
    const { user, modal } = await pedirSaida(fetchMock);

    await user.click(within(modal).getByRole('button', { name: 'Sair da fila' }));

    expect(await screen.findByText(/Esta inscrição já tinha sido encerrada/)).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: 'Sair da fila de espera' })).not.toBeInTheDocument();
    expect(await screen.findByText(/Você não está em nenhuma fila de espera/)).toBeInTheDocument();
  });

  it('mantém o modal aberto com o erro quando o servidor falha', async () => {
    const fetchMock = apiDaFila({ ativas: [AGUARDANDO], aoCancelar: () => json({ erro: 'Serviço indisponível.' }, 503) });
    const { user, modal } = await pedirSaida(fetchMock);

    await user.click(within(modal).getByRole('button', { name: 'Sair da fila' }));

    expect(await within(modal).findByRole('alert')).toHaveTextContent('Não foi possível cancelar a inscrição: Serviço indisponível.');
    expect(screen.getByRole('dialog', { name: 'Sair da fila de espera' })).toBeInTheDocument();
    expect(within(modal).getByRole('button', { name: 'Sair da fila' })).toBeEnabled();
  });
});

describe('vaga sinalizada que se perdeu', () => {
  async function perderVaga(usuario = PACIENTE) {
    let leituras = 0;
    const fetchMock = apiDaFila({ ativas: () => (++leituras === 1 ? [SINALIZADA] : [AGUARDANDO]) });
    const user = userEvent.setup();
    abrirFila(fetchMock, usuario);
    expect(await screen.findByText('Vaga oferecida', { selector: '.badge' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Atualizar lista' }));
    await screen.findByText(/A vaga anteriormente sinalizada para Paulo Paciente não está mais disponível/);
    return user;
  }

  it('mantém o paciente na fila sem oferecer agendamento', async () => {
    await perderVaga();

    expect(screen.getByText(/A inscrição continua aguardando outra oportunidade/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Buscar outra vaga' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Agendar vaga' })).not.toBeInTheDocument();
    expect(screen.getByText('Aguardando', { selector: '.badge' })).toBeInTheDocument();
  });

  it('abre a nova consulta com o médico e a especialidade da vaga perdida', async () => {
    const user = await perderVaga(SECRETARIA_TESTE);

    await user.click(screen.getByRole('link', { name: 'Buscar outra vaga' }));

    expect(await screen.findByRole('heading', { name: 'Nova consulta' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('combobox', { name: /Especialidade/ })).toHaveValue(ESPECIALIDADE_ID));
    await waitFor(() => expect(screen.getByRole('combobox', { name: /Médico/ })).toHaveValue(MEDICO_ID));
  });

  it('some ao dispensar o aviso', async () => {
    const user = await perderVaga(SECRETARIA_TESTE);

    await user.click(screen.getByRole('button', { name: 'Dispensar aviso' }));

    expect(screen.queryByText(/A vaga anteriormente sinalizada/)).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Buscar outra vaga' })).not.toBeInTheDocument();
  });
});
