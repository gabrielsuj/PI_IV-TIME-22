import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { routes } from '../routes';
import { entrarComo } from './sessaoTeste';

const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const erro = (status, mensagem) => json({ status, erro: mensagem }, status);

const PAULO = { id: 'u-paciente', nome: 'Paulo Paciente', perfil: 'PACIENTE' };
const ANA = { id: 'u-medica', nome: 'Ana Martins', perfil: 'MEDICO' };

/** 21/10/2026 09:00 em São Paulo = 12:00Z. */
const consulta = (id, extra = {}) => ({
  id, pacienteId: 'p1', pacienteNome: 'Paulo Paciente', medicoId: 'm1', medicoNome: 'Ana Martins',
  especialidadeId: 'e1', especialidadeNome: 'Cardiologia', inicio: '2026-10-21T12:00:00Z', fim: '2026-10-21T12:30:00Z',
  status: 'AGENDADA', motivoCancelamento: null, podeCancelar: true, ...extra,
});

/**
 * API simulada. `lista` e `detalhes` podem ser funções para mudar a resposta a cada chamada.
 * Devolve o fetch simulado para conferir as chamadas.
 */
function mockApi({ lista = [consulta('c1')], detalhes = { c1: consulta('c1') }, cancelar = () => json(consulta('c1', { status: 'CANCELADA' })) } = {}) {
  const fetchMock = vi.fn(async (url, init = {}) => {
    const { pathname } = new URL(url);
    if (pathname === '/medicos') return json([{ id: 'm1', nome: 'Ana Martins', ativo: true }, { id: 'm2', nome: 'Bruno Lima', ativo: true }]);
    if (pathname === '/consultas/lista') return typeof lista === 'function' ? lista(url) : json(lista);
    const cancelamento = pathname.match(/^\/consultas\/([^/]+)\/cancelamento$/);
    if (cancelamento && init.method === 'POST') return cancelar(cancelamento[1], JSON.parse(init.body ?? '{}'));
    const detalhe = pathname.match(/^\/consultas\/([^/]+)$/);
    if (detalhe) {
      const resposta = typeof detalhes === 'function' ? detalhes(detalhe[1]) : detalhes[detalhe[1]];
      return resposta instanceof Response ? resposta : resposta ? json(resposta) : erro(404, 'Consulta não encontrada');
    }
    return json({}, 404);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

const chamadas = (fetchMock, caminho) => fetchMock.mock.calls.map(([url, init]) => ({ url: new URL(url), init }))
  .filter(({ url }) => url.pathname === caminho);

function renderAt(path) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(<RouterProvider router={router} />);
  return router;
}

const cabecalhos = () => within(screen.getByRole('table')).getAllByRole('columnheader').map((th) => th.textContent);

beforeEach(() => {
  sessionStorage.clear();
  // O jsdom não implementa <dialog>.showModal(); mesmo apoio dos testes de Agenda e Pacientes.
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value() { this.open = true; } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value() { this.open = false; } });
});

describe('listagem de consultas', () => {
  it('paciente vê as próprias consultas próximas, sem a coluna de paciente', async () => {
    entrarComo(PAULO);
    const fetchMock = mockApi();
    renderAt('/consultas');

    expect(await screen.findByRole('heading', { name: 'Minhas consultas' })).toBeInTheDocument();
    expect(await screen.findByText('21/10/2026')).toBeInTheDocument();
    expect(screen.getByText('09:00 às 09:30')).toBeInTheDocument();
    expect(cabecalhos()).toEqual(['Data', 'Horário', 'Médico', 'Especialidade', 'Status', 'Ações']);
    expect(within(screen.getByRole('table')).getByText('Agendada')).toBeInTheDocument();
    expect(chamadas(fetchMock, '/consultas/lista')[0].url.searchParams.get('periodo')).toBe('proximas');
  });

  it('troca para as consultas anteriores e filtra por status e médico', async () => {
    entrarComo(PAULO);
    const fetchMock = mockApi();
    const user = userEvent.setup();
    const router = renderAt('/consultas');
    await screen.findByText('21/10/2026');

    await user.click(screen.getByRole('button', { name: 'Anteriores' }));
    await waitFor(() => expect(chamadas(fetchMock, '/consultas/lista').at(-1).url.searchParams.get('periodo')).toBe('anteriores'));
    expect(router.state.location.search).toContain('periodo=anteriores');

    await user.selectOptions(screen.getByLabelText('Status'), 'CONCLUIDA');
    await waitFor(() => expect(chamadas(fetchMock, '/consultas/lista').at(-1).url.searchParams.get('status')).toBe('CONCLUIDA'));

    await waitFor(() => expect(screen.getByRole('option', { name: 'Bruno Lima' })).toBeInTheDocument());
    await user.selectOptions(screen.getByLabelText('Médico'), 'm2');
    await waitFor(() => expect(chamadas(fetchMock, '/consultas/lista').at(-1).url.searchParams.get('medicoId')).toBe('m2'));
  });

  it('médico não vê a coluna nem o filtro de médico, nem o botão de nova consulta', async () => {
    entrarComo(ANA);
    mockApi();
    renderAt('/consultas');

    await screen.findByText('21/10/2026');
    expect(cabecalhos()).toEqual(['Data', 'Horário', 'Paciente', 'Especialidade', 'Status', 'Ações']);
    expect(screen.queryByLabelText('Médico')).not.toBeInTheDocument();
    expect(within(screen.getByRole('main')).queryByRole('link', { name: 'Nova consulta' })).not.toBeInTheDocument();
  });

  it('secretaria vê paciente e médico', async () => {
    entrarComo();
    mockApi();
    renderAt('/consultas');

    expect(await screen.findByRole('heading', { name: 'Consultas' })).toBeInTheDocument();
    await screen.findByText('21/10/2026');
    expect(cabecalhos()).toEqual(['Data', 'Horário', 'Paciente', 'Médico', 'Especialidade', 'Status', 'Ações']);
  });

  it('mostra mensagem quando não há consultas', async () => {
    entrarComo(PAULO);
    mockApi({ lista: [] });
    renderAt('/consultas');
    expect(await screen.findByText('Nenhuma consulta marcada com estes filtros.')).toBeInTheDocument();
  });

  it('mostra erro e tenta de novo', async () => {
    entrarComo(PAULO);
    let falhar = true;
    mockApi({ lista: () => (falhar ? erro(500, 'Erro interno do servidor') : json([consulta('c1')])) });
    const user = userEvent.setup();
    renderAt('/consultas');

    expect(await screen.findByText('Não foi possível carregar os registros.')).toBeInTheDocument();
    falhar = false;
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));
    expect(await screen.findByText('21/10/2026')).toBeInTheDocument();
  });

  it('login de paciente sem cadastro vê a mensagem do servidor', async () => {
    entrarComo(PAULO);
    mockApi({ lista: () => erro(403, 'Seu login não está ligado a um cadastro de paciente. Procure a secretaria.') });
    renderAt('/consultas');
    expect(await screen.findByText('Seu login não está ligado a um cadastro de paciente. Procure a secretaria.')).toBeInTheDocument();
  });

  it('abre o detalhe pelo botão da linha', async () => {
    entrarComo(PAULO);
    mockApi();
    const user = userEvent.setup();
    const router = renderAt('/consultas');

    await user.click(await screen.findByRole('link', { name: 'Ver detalhes da consulta de 21/10/2026, 09:00 às 09:30' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/consultas/c1'));
    expect(await screen.findByRole('heading', { name: 'Detalhes da consulta' })).toBeInTheDocument();
  });
});

describe('detalhe e cancelamento da consulta', () => {
  it('mostra o resumo da reserva e o botão de cancelar', async () => {
    entrarComo(PAULO);
    mockApi();
    renderAt('/consultas/c1');

    const resumo = await screen.findByRole('region', { name: 'Resumo da reserva' });
    expect(within(resumo).getByText('Paulo Paciente (você)')).toBeInTheDocument();
    expect(within(resumo).getByText('Ana Martins')).toBeInTheDocument();
    expect(within(resumo).getByText('Cardiologia')).toBeInTheDocument();
    expect(within(resumo).getByText('Agendada')).toBeInTheDocument();
    expect(within(resumo).getByRole('button', { name: 'Cancelar consulta' })).toBeInTheDocument();
  });

  it('cancela com motivo, recarrega e mostra a consulta cancelada', async () => {
    entrarComo(PAULO);
    let cancelada = false;
    const fetchMock = mockApi({
      detalhes: () => (cancelada ? consulta('c1', { status: 'CANCELADA', motivoCancelamento: 'Viagem', podeCancelar: false }) : consulta('c1')),
      cancelar: () => { cancelada = true; return json(consulta('c1', { status: 'CANCELADA' })); },
    });
    const user = userEvent.setup();
    renderAt('/consultas/c1');

    await user.click(await screen.findByRole('button', { name: 'Cancelar consulta' }));
    const dialog = screen.getByRole('dialog', { name: 'Cancelar consulta?' });
    await user.type(within(dialog).getByLabelText('Motivo (opcional)'), '  Viagem ');
    await user.click(within(dialog).getByRole('button', { name: 'Confirmar cancelamento' }));

    expect(await screen.findByText('Consulta cancelada. O horário foi liberado para outros pacientes.')).toBeInTheDocument();
    const [envio] = chamadas(fetchMock, '/consultas/c1/cancelamento');
    expect(envio.init.method).toBe('POST');
    expect(JSON.parse(envio.init.body)).toEqual({ motivo: 'Viagem' });
    expect(await screen.findByText('Viagem')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancelar consulta' })).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('cancela sem motivo enviando corpo vazio', async () => {
    entrarComo(PAULO);
    const fetchMock = mockApi();
    const user = userEvent.setup();
    renderAt('/consultas/c1');

    await user.click(await screen.findByRole('button', { name: 'Cancelar consulta' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirmar cancelamento' }));

    await screen.findByText('Consulta cancelada. O horário foi liberado para outros pacientes.');
    expect(JSON.parse(chamadas(fetchMock, '/consultas/c1/cancelamento')[0].init.body)).toEqual({});
  });

  it('409: fecha o modal, mostra o motivo da recusa e recarrega a consulta', async () => {
    entrarComo(PAULO);
    const fetchMock = mockApi({ cancelar: () => erro(409, 'Esta consulta já foi cancelada.') });
    const user = userEvent.setup();
    renderAt('/consultas/c1');

    await user.click(await screen.findByRole('button', { name: 'Cancelar consulta' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirmar cancelamento' }));

    expect(await screen.findByText('Esta consulta já foi cancelada. Os dados abaixo foram atualizados.')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() => expect(chamadas(fetchMock, '/consultas/c1')).toHaveLength(2));
  });

  it('outro erro: mantém o modal aberto com a mensagem para tentar de novo', async () => {
    entrarComo(PAULO);
    mockApi({ cancelar: () => erro(500, 'Erro interno do servidor') });
    const user = userEvent.setup();
    renderAt('/consultas/c1');

    await user.click(await screen.findByRole('button', { name: 'Cancelar consulta' }));
    const dialog = screen.getByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Confirmar cancelamento' }));

    expect(await within(dialog).findByText('Erro interno do servidor')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Confirmar cancelamento' })).toBeEnabled();
  });

  it('consulta ativa que já começou não mostra o botão e explica o motivo', async () => {
    entrarComo(PAULO);
    mockApi({ detalhes: { c1: consulta('c1', { podeCancelar: false }) } });
    renderAt('/consultas/c1');

    expect(await screen.findByText('Esta consulta já começou e não pode mais ser cancelada.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancelar consulta' })).not.toBeInTheDocument();
  });

  it('consulta concluída não mostra cancelamento nem aviso', async () => {
    entrarComo(PAULO);
    mockApi({ detalhes: { c1: consulta('c1', { status: 'CONCLUIDA', podeCancelar: false }) } });
    renderAt('/consultas/c1');

    expect(await screen.findByText('Concluída')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancelar consulta' })).not.toBeInTheDocument();
    expect(screen.queryByText(/já começou/)).not.toBeInTheDocument();
  });

  it.each([
    [404, 'Consulta não encontrada. Ela pode ter sido removida ou o endereço está incorreto.'],
    [403, 'Você não tem acesso a esta consulta.'],
  ])('erro %i mostra mensagem própria', async (status, mensagem) => {
    entrarComo(PAULO);
    mockApi({ detalhes: () => erro(status, 'qualquer') });
    renderAt('/consultas/c1');

    expect(await screen.findByText(mensagem)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Tentar novamente' })).not.toBeInTheDocument();
  });
});
