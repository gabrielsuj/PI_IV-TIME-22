import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
const AGORA = Date.parse('2026-11-18T12:30:00Z');

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
function mockApi({ lista = [consulta('c1')], detalhes = { c1: consulta('c1') }, cancelar = () => json(consulta('c1', { status: 'CANCELADA' })),
  alterarStatus = (_id, pedido) => json(consulta('c1', { status: pedido.status })) } = {}) {
  const fetchMock = vi.fn(async (url, init = {}) => {
    const { pathname } = new URL(url);
    if (pathname === '/medicos') return json([{ id: 'm1', nome: 'Ana Martins', ativo: true }, { id: 'm2', nome: 'Bruno Lima', ativo: true }]);
    if (pathname === '/consultas/lista') return typeof lista === 'function' ? lista(url) : json(lista);
    const cancelamento = pathname.match(/^\/consultas\/([^/]+)\/cancelamento$/);
    if (cancelamento && init.method === 'POST') return cancelar(cancelamento[1], JSON.parse(init.body ?? '{}'));
    const transicao = pathname.match(/^\/consultas\/([^/]+)\/status$/);
    if (transicao && init.method === 'POST') return alterarStatus(transicao[1], JSON.parse(init.body ?? '{}'), init);
    const detalhe = pathname.match(/^\/consultas\/([^/]+)$/);
    if (detalhe) {
      const resposta = await (typeof detalhes === 'function' ? detalhes(detalhe[1]) : detalhes[detalhe[1]]);
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
  vi.spyOn(Date, 'now').mockReturnValue(AGORA);
  // O jsdom não implementa <dialog>.showModal(); mesmo apoio dos testes de Agenda e Pacientes.
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value() { this.open = true; } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value() { this.open = false; } });
});

function pendente() {
  let resolver;
  const promise = new Promise((resolve) => { resolver = resolve; });
  return { promise, resolver };
}

describe('ações de status da consulta (#28)', () => {
  it.each([
    ['PACIENTE', 'AGENDADA', false, ['Confirmar consulta']],
    ['PACIENTE', 'CONFIRMADA', false, []],
    ['PACIENTE', 'CANCELADA', false, []],
    ['PACIENTE', 'CONCLUIDA', false, []],
    ['PACIENTE', 'FALTA', false, []],
    ['MEDICO', 'AGENDADA', false, ['Confirmar consulta', 'Registrar falta']],
    ['MEDICO', 'CONFIRMADA', false, ['Concluir consulta', 'Registrar falta']],
    ['MEDICO', 'AGENDADA', true, ['Confirmar consulta']],
    ['MEDICO', 'CONFIRMADA', true, []],
    ['MEDICO', 'CANCELADA', false, []],
    ['MEDICO', 'CONCLUIDA', false, []],
    ['MEDICO', 'FALTA', false, []],
    ['SECRETARIA_ADMIN', 'AGENDADA', false, ['Confirmar consulta', 'Registrar falta']],
    ['SECRETARIA_ADMIN', 'CONFIRMADA', false, ['Concluir consulta', 'Registrar falta']],
    ['SECRETARIA_ADMIN', 'AGENDADA', true, ['Confirmar consulta']],
    ['SECRETARIA_ADMIN', 'CONFIRMADA', true, []],
    ['SECRETARIA_ADMIN', 'CANCELADA', false, []],
    ['SECRETARIA_ADMIN', 'CONCLUIDA', false, []],
    ['SECRETARIA_ADMIN', 'FALTA', false, []],
  ])('%s em %s (fim futuro: %s) vê somente as ações permitidas', async (perfil, status, futura, esperadas) => {
    entrarComo({ id: 'u-teste', nome: 'Teste', perfil });
    const fetchMock = mockApi({ detalhes: { c1: consulta('c1', { status, podeCancelar: false,
      fim: new Date(AGORA + (futura ? 1000 : 0)).toISOString() }) } });
    renderAt('/consultas/c1');
    const resumo = within(await screen.findByRole('region', { name: 'Resumo da reserva' }));
    for (const acao of ['Confirmar consulta', 'Concluir consulta', 'Registrar falta']) {
      if (esperadas.includes(acao)) expect(resumo.getByRole('button', { name: acao })).toBeEnabled();
      else expect(resumo.queryByRole('button', { name: acao })).not.toBeInTheDocument();
    }
    expect(chamadas(fetchMock, '/consultas/c1/status')).toHaveLength(0);
  });

  it.each([
    ['AGENDADA', 'CONFIRMADA', 'Confirmar consulta', 'Consulta confirmada.', 'Confirmada'],
    ['CONFIRMADA', 'CONCLUIDA', 'Concluir consulta', 'Consulta concluída.', 'Concluída'],
    ['AGENDADA', 'FALTA', 'Registrar falta', 'Falta registrada.', 'Falta'],
  ])('envia somente %s → %s e atualiza o detalhe após confirmação da API', async (anterior, destino, rotulo, sucesso, badge) => {
    entrarComo(ANA);
    let atual = consulta('c1', { status: anterior, podeCancelar: false });
    const fetchMock = mockApi({ detalhes: () => atual, alterarStatus: (_id, pedido) => {
      atual = { ...atual, status: pedido.status };
      return json(atual);
    } });
    const user = userEvent.setup();
    renderAt('/consultas/c1');
    const botao = await screen.findByRole('button', { name: rotulo });
    if (destino === 'CONFIRMADA') {
      botao.focus();
      await user.keyboard('{Enter}');
    } else await user.click(botao);
    const mensagem = await screen.findByText(sucesso);
    expect(mensagem.closest('[role="status"]')).toHaveFocus();
    expect(await within(screen.getByRole('region', { name: 'Resumo da reserva' })).findByText(badge)).toBeInTheDocument();
    expect(chamadas(fetchMock, '/consultas/c1')).toHaveLength(2);
    const [envio] = chamadas(fetchMock, '/consultas/c1/status');
    expect(envio.init.method).toBe('POST');
    expect(envio.init.headers.Authorization).toBe('Bearer token-de-teste');
    expect(JSON.parse(envio.init.body)).toEqual({ status: destino });
    expect(chamadas(fetchMock, '/consultas/c1/status')).toHaveLength(1);
  });

  it('bloqueia envio duplo, cancelamento e volta enquanto altera o status', async () => {
    entrarComo(PAULO);
    const escrita = pendente();
    const fetchMock = mockApi({ alterarStatus: () => escrita.promise });
    renderAt('/consultas/c1');
    const botao = await screen.findByRole('button', { name: 'Confirmar consulta' });
    act(() => { fireEvent.click(botao); fireEvent.click(botao); });
    expect(chamadas(fetchMock, '/consultas/c1/status')).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Confirmando…' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Cancelar consulta' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '‹ Voltar para consultas' })).toBeDisabled();
    expect(screen.queryByText('Consulta confirmada.')).not.toBeInTheDocument();
    await act(async () => { escrita.resolver(json(consulta('c1', { status: 'CONFIRMADA' }))); });
  });

  it('mantém ações de status bloqueadas enquanto o modal de cancelamento está aberto', async () => {
    entrarComo(PAULO);
    mockApi();
    const user = userEvent.setup();
    renderAt('/consultas/c1');
    await user.click(await screen.findByRole('button', { name: 'Cancelar consulta' }));
    expect(screen.getByRole('button', { name: 'Confirmar consulta' })).toBeDisabled();
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Voltar' }));
    expect(screen.getByRole('button', { name: 'Confirmar consulta' })).toBeEnabled();
  });

  it('409 recarrega o estado e impede nova ação durante a leitura', async () => {
    entrarComo(ANA);
    const atualizacao = pendente();
    let leitura = 0;
    const fetchMock = mockApi({ detalhes: () => ++leitura === 1 ? consulta('c1', { podeCancelar: false }) : atualizacao.promise,
      alterarStatus: () => erro(409, 'Consulta alterada simultaneamente.') });
    const user = userEvent.setup();
    renderAt('/consultas/c1');
    await user.click(await screen.findByRole('button', { name: 'Confirmar consulta' }));
    expect(await screen.findByText('Consulta alterada simultaneamente.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirmar consulta' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Registrar falta' })).toBeDisabled();
    await act(async () => { atualizacao.resolver(consulta('c1', { status: 'CANCELADA', podeCancelar: false })); });
    expect(await screen.findByText('Cancelada')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Confirmar consulta' })).not.toBeInTheDocument();
    expect(chamadas(fetchMock, '/consultas/c1')).toHaveLength(2);
    expect(chamadas(fetchMock, '/consultas/c1/status')).toHaveLength(1);
  });

  it.each([400, 503])('erro %i mantém contexto e libera controles sem repetição automática', async (status) => {
    entrarComo(ANA);
    const fetchMock = mockApi({ alterarStatus: () => erro(status, 'Alteração recusada pela API.') });
    const user = userEvent.setup();
    renderAt('/consultas/c1');
    await user.click(await screen.findByRole('button', { name: 'Confirmar consulta' }));
    expect((await screen.findByText('Alteração recusada pela API.')).closest('[role="alert"]')).toHaveFocus();
    expect(screen.getByText('Paulo Paciente')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirmar consulta' })).toBeEnabled();
    expect(chamadas(fetchMock, '/consultas/c1/status')).toHaveLength(1);
    expect(chamadas(fetchMock, '/consultas/c1')).toHaveLength(1);
  });

  it.each([403, 404])('erro %i na ação exige conferência e respeita recusa da leitura', async (status) => {
    entrarComo(PAULO);
    let recusada = false;
    const fetchMock = mockApi({ detalhes: () => recusada ? erro(status, 'Acesso recusado.') : consulta('c1'),
      alterarStatus: () => { recusada = true; return erro(status, 'Não foi possível alterar esta consulta.'); } });
    const user = userEvent.setup();
    renderAt('/consultas/c1');
    await user.click(await screen.findByRole('button', { name: 'Confirmar consulta' }));
    expect(await screen.findByText('Não foi possível alterar esta consulta.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirmar consulta' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Conferir status da consulta' }));
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Resumo da reserva' })).not.toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'Conferir status da consulta' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Tentar novamente' })).not.toBeInTheDocument();
    expect(chamadas(fetchMock, '/consultas/c1/status')).toHaveLength(1);
  });

  it.each([
    ['rede', () => { throw new TypeError('Failed to fetch'); }],
    ['timeout', (_id, _pedido, init) => {
      vi.spyOn(init.signal, 'aborted', 'get').mockReturnValue(true);
      throw new DOMException('Tempo esgotado', 'TimeoutError');
    }],
    ['leitura interrompida', () => ({ status: 200, ok: true, text: () => Promise.reject(new TypeError('Conexão perdida')) })],
    ['resposta incompleta', () => json({})],
    ['resposta de outra consulta', () => json(consulta('c2', { status: 'CONFIRMADA' }))],
    ['HTTP 500', () => erro(500, 'Erro interno')],
  ])('resultado incerto por %s exige GET explícito antes de outra ação', async (_caso, responder) => {
    entrarComo(PAULO);
    let alterada = false;
    const fetchMock = mockApi({ detalhes: () => consulta('c1', { status: alterada ? 'CONFIRMADA' : 'AGENDADA', podeCancelar: false }),
      alterarStatus: (...args) => { alterada = true; return responder(...args); } });
    const user = userEvent.setup();
    renderAt('/consultas/c1');
    await user.click(await screen.findByRole('button', { name: 'Confirmar consulta' }));
    expect(await screen.findByText(/Não foi possível verificar o resultado da alteração/)).toBeInTheDocument();
    expect(screen.queryByText('Consulta confirmada.')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirmar consulta' })).toBeDisabled();
    expect(chamadas(fetchMock, '/consultas/c1')).toHaveLength(1);
    expect(chamadas(fetchMock, '/consultas/c1/status')).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: 'Conferir status da consulta' }));
    expect(await screen.findByText('Confirmada')).toBeInTheDocument();
    expect(screen.getByText('Status atualizado. Confira o resumo antes de decidir sobre outra alteração.')).toBeInTheDocument();
    expect(chamadas(fetchMock, '/consultas/c1')).toHaveLength(2);
    expect(chamadas(fetchMock, '/consultas/c1/status')).toHaveLength(1);
  });

  it('confirmação salva continua anunciada se a atualização do detalhe falhar, e a nova tentativa só faz GET', async () => {
    entrarComo(PAULO);
    let leitura = 0;
    const fetchMock = mockApi({ detalhes: () => {
      leitura++;
      if (leitura === 2) return erro(500, 'Falha de leitura.');
      return consulta('c1', { status: leitura > 2 ? 'CONFIRMADA' : 'AGENDADA' });
    } });
    const user = userEvent.setup();
    renderAt('/consultas/c1');
    await user.click(await screen.findByRole('button', { name: 'Confirmar consulta' }));
    expect(await screen.findByText(/O resumo abaixo pode estar desatualizado/)).toBeInTheDocument();
    expect(screen.getByText('Consulta confirmada.')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Resumo da reserva' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirmar consulta' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));
    expect(await screen.findByText('Confirmada')).toBeInTheDocument();
    expect(chamadas(fetchMock, '/consultas/c1/status')).toHaveLength(1);
  });

  it('não deixa a resposta de uma ação antiga alterar o detalhe de outra consulta', async () => {
    entrarComo(PAULO);
    const escrita = pendente();
    const fetchMock = mockApi({ detalhes: { c1: consulta('c1'), c2: consulta('c2', { medicoNome: 'Bruno Lima' }) }, alterarStatus: () => escrita.promise });
    const user = userEvent.setup();
    const router = renderAt('/consultas/c1');
    await user.click(await screen.findByRole('button', { name: 'Confirmar consulta' }));
    await act(async () => { await router.navigate('/consultas/c2'); });
    await screen.findByText('Bruno Lima');
    await act(async () => { escrita.resolver(json(consulta('c1', { status: 'CONFIRMADA' }))); });
    expect(screen.queryByText('Consulta confirmada.')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirmar consulta' })).toBeEnabled();
    expect(chamadas(fetchMock, '/consultas/c2')).toHaveLength(1);
    expect(chamadas(fetchMock, '/consultas/c2/status')).toHaveLength(0);
  });

  it('401 volta ao login e não reenvia a transição após reautenticação', async () => {
    entrarComo(PAULO);
    const fetchMock = mockApi({ alterarStatus: () => erro(401, 'Sessão expirada.') });
    const responder = fetchMock.getMockImplementation();
    fetchMock.mockImplementation((url, init) => new URL(url).pathname === '/auth/login'
      ? Promise.resolve(json({ token: 'novo-token', usuario: PAULO })) : responder(url, init));
    const user = userEvent.setup();
    renderAt('/consultas/c1');
    await user.click(await screen.findByRole('button', { name: 'Confirmar consulta' }));
    await user.type(await screen.findByLabelText(/E-mail/), 'paulo@example.com');
    await user.type(screen.getByLabelText(/Senha/), 'senha-de-teste{Enter}');
    expect(await screen.findByRole('button', { name: 'Confirmar consulta' })).toBeEnabled();
    expect(chamadas(fetchMock, '/consultas/c1/status')).toHaveLength(1);
  });

  it('libera conclusão e falta ao atingir o fim previsto com a tela aberta', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      vi.mocked(Date.now).mockReturnValue(AGORA - 1);
      entrarComo(ANA);
      mockApi({ detalhes: { c1: consulta('c1', { status: 'CONFIRMADA', fim: new Date(AGORA).toISOString(), podeCancelar: false }) } });
      await act(async () => { renderAt('/consultas/c1'); });
      expect(screen.queryByRole('button', { name: 'Concluir consulta' })).not.toBeInTheDocument();
      vi.mocked(Date.now).mockReturnValue(AGORA);
      await act(async () => { await vi.advanceTimersByTimeAsync(1); });
      expect(screen.getByRole('button', { name: 'Concluir consulta' })).toBeEnabled();
      expect(screen.getByRole('button', { name: 'Registrar falta' })).toBeEnabled();
    } finally { vi.useRealTimers(); }
  });
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
