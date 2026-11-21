/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

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
  alterarStatus = (_id, pedido) => json(consulta('c1', { status: pedido.status })), historico = [],
  medicos = () => json([{ id: 'm1', nome: 'Ana Martins', ativo: true }, { id: 'm2', nome: 'Bruno Lima', ativo: true }]) } = {}) {
  const fetchMock = vi.fn(async (url, init = {}) => {
    const { pathname } = new URL(url);
    if (pathname === '/medicos') return medicos();
    if (pathname === '/consultas/lista') return typeof lista === 'function' ? lista(url) : json(lista);
    const cancelamento = pathname.match(/^\/consultas\/([^/]+)\/cancelamento$/);
    if (cancelamento && init.method === 'POST') return cancelar(cancelamento[1], JSON.parse(init.body ?? '{}'));
    const transicao = pathname.match(/^\/consultas\/([^/]+)\/status$/);
    if (transicao && init.method === 'POST') return alterarStatus(transicao[1], JSON.parse(init.body ?? '{}'), init);
    const historicoId = pathname.match(/^\/consultas\/([^/]+)\/historico$/);
    if (historicoId && init.method === 'GET') {
      const parametros = new URL(url).searchParams;
      const resposta = await (typeof historico === 'function' ? historico(historicoId[1], parametros, init)
        : paginaHistorico(historicoId[1], historico, parametros));
      return resposta instanceof Response ? resposta : json(resposta);
    }
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

function paginaHistorico(consultaId, eventos, parametros = new URLSearchParams()) {
  const aposSequencia = Number(parametros.get('aposSequencia') || 0);
  const limite = Number(parametros.get('limite') || 20);
  const restantes = eventos.filter((e) => e.sequencia > aposSequencia).sort((a, b) => a.sequencia - b.sequencia);
  const pagina = restantes.slice(0, limite);
  return { consultaId, eventos: pagina, aposSequencia, limite, limiteAtingido: pagina.length === limite,
    atualizacaoPendente: false, temMais: restantes.length > limite,
    ...(restantes.length > limite ? { proximaSequencia: pagina.at(-1).sequencia } : {}) };
}

const eventoHistorico = (sequencia, extra = {}) => ({ id: `h${sequencia}`, sequencia, evento: 'CRIACAO', statusAnterior: null,
  statusNovo: 'AGENDADA', ocorridoEm: '2026-11-18T12:30:00Z', dataHoraConsulta: '2026-12-01T12:00:00Z',
  ator: { id: 'u-secretaria', perfil: 'SECRETARIA_ADMIN', nome: 'Sofia Secretaria' }, ...extra });

// O instante fixo permite conferir as ações de status antes e depois do fim previsto
// sem depender do relógio da máquina.
describe('histórico no detalhe da consulta (#46)', () => {
  it.each([
    ['CRIACAO', 'Consulta criada', {}],
    ['CONFIRMACAO', 'Consulta confirmada', { statusAnterior: 'AGENDADA', statusNovo: 'CONFIRMADA' }],
    ['ALTERACAO', 'Consulta alterada', { statusAnterior: 'CONFIRMADA', statusNovo: 'CONFIRMADA', alteracoes: [
      { campo: 'inicio', valorAnterior: '2026-12-01T12:00:00Z', valorNovo: '2026-12-02T13:00:00Z' },
      { campo: 'medicoId', valorAnterior: 'id-anterior', valorNovo: 'id-novo' },
      { campo: 'especialidadeId', valorAnterior: null, valorNovo: 'e2' },
    ] }],
    ['CANCELAMENTO', 'Consulta cancelada', { statusAnterior: 'CONFIRMADA', statusNovo: 'CANCELADA', motivo: 'Viagem' }],
    ['CONCLUSAO', 'Consulta concluída', { statusAnterior: 'CONFIRMADA', statusNovo: 'CONCLUIDA' }],
    ['FALTA', 'Falta registrada', { statusAnterior: 'CONFIRMADA', statusNovo: 'FALTA' }],
    ['SINALIZACAO_FILA', 'Fila de espera sinalizada', { statusAnterior: 'CANCELADA', statusNovo: 'CANCELADA', totalSinalizados: 0,
      ator: { perfil: 'SISTEMA', id: null, nome: 'Sistema' } }],
  ])('exibe %s com responsável, data do evento e detalhes próprios', async (evento, rotulo, extra) => {
    entrarComo(PAULO);
    const fetchMock = mockApi({ historico: [eventoHistorico(1, { evento, ...extra })] });
    renderAt('/consultas/c1');
    const historico = within(await screen.findByRole('region', { name: 'Histórico' }));
    expect(await historico.findByRole('heading', { name: rotulo })).toBeInTheDocument();
    expect(historico.getByText('18/11/2026, 09:30')).toHaveAttribute('dateTime', '2026-11-18T12:30:00Z');
    expect(historico.getByText(evento === 'SINALIZACAO_FILA' ? 'Sistema' : 'Sofia Secretaria · Secretaria / Administrador')).toBeInTheDocument();
    if (evento === 'ALTERACAO') {
      expect(historico.getByText('Início da consulta: 01/12/2026, 09:00 → 02/12/2026, 10:00.')).toBeInTheDocument();
      expect(historico.getByText('Médico alterado.')).toBeInTheDocument();
      expect(historico.getByText('Especialidade alterada.')).toBeInTheDocument();
      expect(historico.queryByText('id-anterior')).not.toBeInTheDocument();
    }
    if (evento === 'CANCELAMENTO') expect(historico.getByText('Viagem')).toBeInTheDocument();
    if (evento === 'SINALIZACAO_FILA') expect(historico.getByText('Interessados sinalizados: 0.')).toBeInTheDocument();
    expect(chamadas(fetchMock, '/consultas/c1/historico')).toHaveLength(1);
    expect(fetchMock.mock.calls.every(([, init]) => init.method === 'GET')).toBe(true);
  });

  it.each(['PACIENTE', 'MEDICO', 'SECRETARIA_ADMIN'])('%s lê o histórico da consulta autorizada sem comparar IDs de login e cadastro', async (perfil) => {
    entrarComo({ id: 'login-diferente-do-cadastro', nome: 'Teste', perfil });
    const fetchMock = mockApi({ historico: [eventoHistorico(1)] });
    renderAt('/consultas/c1');
    await within(await screen.findByRole('region', { name: 'Histórico' })).findByRole('heading', { name: 'Consulta criada' });
    const [envio] = chamadas(fetchMock, '/consultas/c1/historico');
    expect(envio.init.headers.Authorization).toBe('Bearer token-de-teste');
    expect(Object.fromEntries(envio.url.searchParams)).toEqual({ aposSequencia: '0', limite: '20' });
  });

  it('carregamento e falha do histórico não escondem o resumo nem suas ações', async () => {
    entrarComo(PAULO);
    const leitura = pendente();
    mockApi({ historico: () => leitura.promise });
    renderAt('/consultas/c1');
    const historico = within(await screen.findByRole('region', { name: 'Histórico' }));
    expect(historico.getByText('Carregando histórico…')).toHaveAttribute('role', 'status');
    expect(screen.getByRole('region', { name: 'Resumo da reserva' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirmar consulta' })).toBeEnabled();
    expect(historico.queryByText('Nenhum evento disponível no momento.')).not.toBeInTheDocument();
    await act(async () => { leitura.resolver(erro(500, 'Falha na leitura do histórico.')); });
    expect(await historico.findByRole('alert')).toHaveFocus();
    expect(screen.getByRole('region', { name: 'Resumo da reserva' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirmar consulta' })).toBeEnabled();
  });

  it('vazio não afirma inexistência de ações e permite atualizar com o teclado', async () => {
    entrarComo(PAULO);
    const eventos = [];
    const fetchMock = mockApi({ historico: eventos });
    const user = userEvent.setup();
    renderAt('/consultas/c1');
    const historico = within(await screen.findByRole('region', { name: 'Histórico' }));
    await historico.findByText('Nenhum evento disponível no momento.');
    eventos.push(eventoHistorico(1));
    historico.getByRole('button', { name: 'Atualizar histórico' }).focus();
    await user.keyboard('{Enter}');
    expect(await historico.findByRole('heading', { name: 'Consulta criada' })).toBeInTheDocument();
    expect(chamadas(fetchMock, '/consultas/c1/historico')).toHaveLength(2);
  });

  it.each([0, 500, 503])('falha %s permite repetir apenas a leitura do histórico', async (status) => {
    entrarComo(PAULO);
    let tentativa = 0;
    const fetchMock = mockApi({ historico: (id, parametros) => {
      if (tentativa++ === 0) {
        if (status === 0) throw new TypeError('Failed to fetch');
        return erro(status, 'Histórico temporariamente indisponível.');
      }
      return paginaHistorico(id, [eventoHistorico(1)], parametros);
    } });
    const user = userEvent.setup();
    renderAt('/consultas/c1');
    const historico = within(await screen.findByRole('region', { name: 'Histórico' }));
    expect(await historico.findByRole('alert')).toBeInTheDocument();
    await user.click(historico.getByRole('button', { name: 'Tentar novamente' }));
    expect(await historico.findByRole('heading', { name: 'Consulta criada' })).toBeInTheDocument();
    expect(chamadas(fetchMock, '/consultas/c1/historico')).toHaveLength(2);
    expect(fetchMock.mock.calls.every(([, init]) => init.method === 'GET')).toBe(true);
  });

  it.each([400, 403, 404])('histórico com erro %s permanece isolado e não oferece tentativa cega', async (status) => {
    entrarComo(PAULO);
    mockApi({ historico: () => erro(status, 'Recusado.') });
    renderAt('/consultas/c1');
    const historico = within(await screen.findByRole('region', { name: 'Histórico' }));
    await historico.findByRole('alert');
    expect(historico.queryByRole('button', { name: 'Tentar novamente' })).not.toBeInTheDocument();
    expect(historico.queryByRole('button', { name: 'Atualizar histórico' })).not.toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Resumo da reserva' })).toBeInTheDocument();
  });

  it('timeout do histórico apresenta erro sem fingir que a lista está vazia', async () => {
    entrarComo(PAULO);
    const relogios = [];
    vi.spyOn(AbortSignal, 'timeout').mockImplementation(() => {
      const controller = new AbortController();
      relogios.push(controller);
      return controller.signal;
    });
    mockApi({ historico: () => {
      const falha = new DOMException('Tempo esgotado', 'TimeoutError');
      relogios.at(-1).abort(falha);
      throw falha;
    } });
    renderAt('/consultas/c1');
    const historico = within(await screen.findByRole('region', { name: 'Histórico' }));
    expect(await historico.findByRole('alert')).toHaveTextContent('A API demorou para responder.');
    expect(historico.queryByText('Nenhum evento disponível no momento.')).not.toBeInTheDocument();
  });

  it('401 no histórico encerra a sessão sem repetir a leitura nem enviar ações', async () => {
    entrarComo(PAULO);
    const fetchMock = mockApi({ historico: () => erro(401, 'Sessão expirada.') });
    renderAt('/consultas/c1');
    await screen.findByRole('heading', { name: 'Acesse sua conta' });
    expect(screen.queryByRole('region', { name: 'Histórico' })).not.toBeInTheDocument();
    expect(sessionStorage.length).toBe(0);
    expect(chamadas(fetchMock, '/consultas/c1/historico')).toHaveLength(1);
    expect(fetchMock.mock.calls.every(([, init]) => init.method === 'GET')).toBe(true);
  });

  it('403 após atualização retira os eventos antigos e mantém a recusa isolada no card', async () => {
    entrarComo(PAULO);
    let recusado = false;
    mockApi({ historico: (id, parametros) => recusado ? erro(403, 'Acesso recusado.') : paginaHistorico(id, [eventoHistorico(1)], parametros) });
    const user = userEvent.setup();
    renderAt('/consultas/c1');
    const historico = within(await screen.findByRole('region', { name: 'Histórico' }));
    await historico.findByRole('heading', { name: 'Consulta criada' });
    recusado = true;
    await user.click(historico.getByRole('button', { name: 'Atualizar histórico' }));
    await historico.findByRole('alert');
    expect(historico.queryByRole('list', { name: 'Eventos do histórico' })).not.toBeInTheDocument();
    expect(historico.queryByRole('button', { name: 'Tentar novamente' })).not.toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Resumo da reserva' })).toBeInTheDocument();
  });

  it('pagina sem duplicar chamadas e leva o foco ao primeiro evento acrescentado', async () => {
    entrarComo(PAULO);
    const eventos = Array.from({ length: 21 }, (_, indice) => eventoHistorico(indice + 1));
    const segunda = pendente();
    const fetchMock = mockApi({ historico: (id, parametros) => Number(parametros.get('aposSequencia')) === 20
      ? segunda.promise : paginaHistorico(id, eventos, parametros) });
    renderAt('/consultas/c1');
    const historico = within(await screen.findByRole('region', { name: 'Histórico' }));
    const mais = await historico.findByRole('button', { name: 'Carregar mais eventos' });
    expect(historico.getAllByRole('listitem')).toHaveLength(20);
    act(() => { fireEvent.click(mais); fireEvent.click(mais); });
    expect(mais).toBeDisabled();
    expect(chamadas(fetchMock, '/consultas/c1/historico')).toHaveLength(2);
    await act(async () => { segunda.resolver(paginaHistorico('c1', eventos, new URLSearchParams({ aposSequencia: '20', limite: '20' }))); });
    const itens = historico.getAllByRole('listitem');
    expect(itens).toHaveLength(21);
    expect(within(itens.at(-1)).getByRole('heading')).toHaveFocus();
    expect(historico.queryByRole('button', { name: 'Carregar mais eventos' })).not.toBeInTheDocument();
    expect(chamadas(fetchMock, '/consultas/c1/historico')[1].url.searchParams.get('aposSequencia')).toBe('20');
  });

  it('falha na próxima página mantém os eventos e a nova tentativa usa o mesmo cursor', async () => {
    entrarComo(PAULO);
    const eventos = Array.from({ length: 21 }, (_, indice) => eventoHistorico(indice + 1));
    let falhar = true;
    const fetchMock = mockApi({ historico: (id, parametros) => {
      if (Number(parametros.get('aposSequencia')) === 20 && falhar) { falhar = false; return erro(500, 'Falha na próxima página.'); }
      return paginaHistorico(id, eventos, parametros);
    } });
    const user = userEvent.setup();
    renderAt('/consultas/c1');
    const historico = within(await screen.findByRole('region', { name: 'Histórico' }));
    await user.click(await historico.findByRole('button', { name: 'Carregar mais eventos' }));
    expect(await historico.findByRole('alert')).toHaveTextContent('Os eventos já exibidos foram mantidos');
    expect(historico.getAllByRole('listitem')).toHaveLength(20);
    await user.click(historico.getByRole('button', { name: 'Tentar novamente' }));
    await waitFor(() => expect(historico.getAllByRole('listitem')).toHaveLength(21));
    expect(chamadas(fetchMock, '/consultas/c1/historico').map(({ url }) => url.searchParams.get('aposSequencia'))).toEqual(['0', '20', '20']);
  });

  it.each(['confirmar', 'concluir', 'falta', 'cancelar'])('atualiza o histórico após %s sem repetir a escrita', async (acao) => {
    entrarComo(ANA);
    const destinos = { confirmar: ['CONFIRMADA', 'CONFIRMACAO', 'Confirmar consulta', 'Consulta confirmada'],
      concluir: ['CONCLUIDA', 'CONCLUSAO', 'Concluir consulta', 'Consulta concluída'],
      falta: ['FALTA', 'FALTA', 'Registrar falta', 'Falta registrada'], cancelar: ['CANCELADA', 'CANCELAMENTO', 'Cancelar consulta', 'Consulta cancelada'] };
    const [destino, evento, botao, titulo] = destinos[acao];
    let atual = consulta('c1', { status: acao === 'concluir' ? 'CONFIRMADA' : 'AGENDADA' });
    const eventos = [eventoHistorico(1)];
    const salvar = () => { atual = { ...atual, status: destino, podeCancelar: false }; eventos.push(eventoHistorico(2, { evento, statusAnterior: 'AGENDADA', statusNovo: destino })); return json(atual); };
    const fetchMock = mockApi({ detalhes: () => atual, alterarStatus: salvar, cancelar: salvar, historico: eventos });
    const user = userEvent.setup();
    renderAt('/consultas/c1');
    await within(await screen.findByRole('region', { name: 'Histórico' })).findByRole('heading', { name: 'Consulta criada' });
    await user.click(screen.getByRole('button', { name: botao }));
    if (acao === 'cancelar') await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirmar cancelamento' }));
    const historico = within(await screen.findByRole('region', { name: 'Histórico' }));
    expect(await historico.findByRole('heading', { name: titulo })).toBeInTheDocument();
    expect(chamadas(fetchMock, '/consultas/c1/historico')).toHaveLength(2);
    expect(fetchMock.mock.calls.filter(([, init]) => init.method === 'POST')).toHaveLength(1);
  });

  it('descarta leitura antiga ao navegar para outra consulta', async () => {
    entrarComo(PAULO);
    const antiga = pendente();
    const fetchMock = mockApi({ detalhes: { c1: consulta('c1'), c2: consulta('c2') },
      historico: (id, parametros) => id === 'c1' ? antiga.promise : paginaHistorico(id, [eventoHistorico(1, { evento: 'CONCLUSAO' })], parametros) });
    const router = renderAt('/consultas/c1');
    await screen.findByRole('region', { name: 'Histórico' });
    const sinal = chamadas(fetchMock, '/consultas/c1/historico')[0].init.signal;
    await act(async () => { await router.navigate('/consultas/c2'); });
    const historico = within(await screen.findByRole('region', { name: 'Histórico' }));
    await historico.findByRole('heading', { name: 'Consulta concluída' });
    expect(sinal.aborted).toBe(true);
    await act(async () => { antiga.resolver(paginaHistorico('c1', [eventoHistorico(1)])); });
    expect(historico.queryByRole('heading', { name: 'Consulta criada' })).not.toBeInTheDocument();
  });

  it('pendência sem eventos mostra aviso e uma nova leitura exibe somente eventos recebidos', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      entrarComo(PAULO);
      let primeira = true;
      const leitura = pendente();
      const fetchMock = mockApi({ historico: (id, parametros) => {
        if (!primeira) return leitura.promise;
        primeira = false;
        return { ...paginaHistorico(id, [], parametros), atualizacaoPendente: true };
      } });
      await act(async () => { renderAt('/consultas/c1'); });
      const historico = within(screen.getByRole('region', { name: 'Histórico' }));
      expect(historico.getByText(/Há atualizações recentes/)).toBeInTheDocument();
      expect(historico.queryByText('Nenhum evento disponível no momento.')).not.toBeInTheDocument();
      expect(historico.queryByRole('list')).not.toBeInTheDocument();
      await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
      expect(historico.getByRole('button', { name: 'Atualizar histórico' })).toBeDisabled();
      await act(async () => { leitura.resolver(paginaHistorico('c1', [eventoHistorico(1)])); });
      expect(historico.getByRole('heading', { name: 'Consulta criada' })).toBeInTheDocument();
      expect(historico.queryByText(/Há atualizações recentes/)).not.toBeInTheDocument();
      await act(async () => { await vi.advanceTimersByTimeAsync(20000); });
      expect(chamadas(fetchMock, '/consultas/c1/historico')).toHaveLength(2);
      expect(fetchMock.mock.calls.every(([, init]) => init.method === 'GET')).toBe(true);
    } finally { vi.useRealTimers(); }
  });

  it('limita a três atualizações automáticas e permite reiniciar pela atualização manual', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      entrarComo(PAULO);
      const fetchMock = mockApi({ historico: (id, parametros) => ({
        ...paginaHistorico(id, [eventoHistorico(1)], parametros), atualizacaoPendente: true,
      }) });
      await act(async () => { renderAt('/consultas/c1'); });
      const historico = within(screen.getByRole('region', { name: 'Histórico' }));
      for (let tentativa = 0; tentativa < 3; tentativa++) {
        await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
        expect(chamadas(fetchMock, '/consultas/c1/historico')).toHaveLength(tentativa + 2);
      }
      expect(historico.getByText(/atualização automática foi pausada/)).toBeInTheDocument();
      expect(historico.getAllByRole('listitem')).toHaveLength(1);
      await act(async () => { await vi.advanceTimersByTimeAsync(20000); });
      expect(chamadas(fetchMock, '/consultas/c1/historico')).toHaveLength(4);
      await act(async () => { fireEvent.click(historico.getByRole('button', { name: 'Atualizar histórico' })); });
      expect(chamadas(fetchMock, '/consultas/c1/historico')).toHaveLength(5);
      expect(historico.getByText(/Há atualizações recentes/)).toBeInTheDocument();
      await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
      expect(chamadas(fetchMock, '/consultas/c1/historico')).toHaveLength(6);
      expect(fetchMock.mock.calls.every(([, init]) => init.method === 'GET')).toBe(true);
    } finally { vi.useRealTimers(); }
  });

  it('falha na atualização automática preserva eventos e interrompe novas tentativas', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      entrarComo(PAULO);
      let leituras = 0;
      const fetchMock = mockApi({ historico: (id, parametros) => ++leituras === 1
        ? { ...paginaHistorico(id, [eventoHistorico(1)], parametros), atualizacaoPendente: true }
        : erro(500, 'Falha temporária no histórico.') });
      await act(async () => { renderAt('/consultas/c1'); });
      const historico = within(screen.getByRole('region', { name: 'Histórico' }));
      await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
      expect(historico.getByText(/Falha temporária no histórico/)).toBeInTheDocument();
      expect(historico.getByRole('heading', { name: 'Consulta criada' })).toBeInTheDocument();
      expect(historico.getByText(/Falha temporária no histórico/)).not.toHaveFocus();
      expect(screen.getByRole('region', { name: 'Resumo da reserva' })).toBeInTheDocument();
      await act(async () => { await vi.advanceTimersByTimeAsync(20000); });
      expect(chamadas(fetchMock, '/consultas/c1/historico')).toHaveLength(2);
    } finally { vi.useRealTimers(); }
  });

  it('sair da consulta cancela a atualização automática agendada', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      entrarComo(PAULO);
      const fetchMock = mockApi({ historico: (id, parametros) => ({
        ...paginaHistorico(id, [eventoHistorico(1)], parametros), atualizacaoPendente: true,
      }) });
      let router;
      await act(async () => { router = renderAt('/consultas/c1'); });
      expect(screen.getByText(/Há atualizações recentes/)).toBeInTheDocument();
      await act(async () => { await router.navigate('/consultas'); });
      await act(async () => { await vi.advanceTimersByTimeAsync(20000); });
      expect(chamadas(fetchMock, '/consultas/c1/historico')).toHaveLength(1);
    } finally { vi.useRealTimers(); }
  });

  it('paginação pausa as leituras automáticas e mantém aviso até conferir o início', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      entrarComo(PAULO);
      const eventos = Array.from({ length: 21 }, (_, indice) => eventoHistorico(indice + 1));
      let leituras = 0;
      const fetchMock = mockApi({ historico: (id, parametros) => ({
        ...paginaHistorico(id, eventos, parametros), atualizacaoPendente: ++leituras === 1,
      }) });
      await act(async () => { renderAt('/consultas/c1'); });
      const historico = within(screen.getByRole('region', { name: 'Histórico' }));
      await act(async () => { fireEvent.click(historico.getByRole('button', { name: 'Carregar mais eventos' })); });
      expect(historico.getAllByRole('listitem')).toHaveLength(21);
      expect(historico.getByText(/atualização automática foi pausada/)).toBeInTheDocument();
      await act(async () => { await vi.advanceTimersByTimeAsync(20000); });
      expect(chamadas(fetchMock, '/consultas/c1/historico')).toHaveLength(2);
      await act(async () => { fireEvent.click(historico.getByRole('button', { name: 'Atualizar histórico' })); });
      expect(historico.getAllByRole('listitem')).toHaveLength(20);
      expect(historico.queryByText(/histórico ainda pode estar incompleto/)).not.toBeInTheDocument();
      expect(chamadas(fetchMock, '/consultas/c1/historico').map(({ url }) => url.searchParams.get('aposSequencia'))).toEqual(['0', '20', '0']);
    } finally { vi.useRealTimers(); }
  });

  it('ação salva continua confirmada enquanto só a leitura do histórico é repetida', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      entrarComo(PAULO);
      let atual = consulta('c1');
      const eventos = [eventoHistorico(1)];
      const fetchMock = mockApi({ detalhes: () => atual,
        alterarStatus: () => { atual = { ...atual, status: 'CONFIRMADA', podeCancelar: false }; return json(atual); },
        historico: (id, parametros) => ({ ...paginaHistorico(id, eventos, parametros), atualizacaoPendente: atual.status === 'CONFIRMADA' && eventos.length === 1 }),
      });
      await act(async () => { renderAt('/consultas/c1'); });
      await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Confirmar consulta' })); });
      const historico = within(screen.getByRole('region', { name: 'Histórico' }));
      expect(screen.getByText('Consulta confirmada.')).toBeInTheDocument();
      expect(historico.getByText(/Ações já confirmadas não precisam ser repetidas/)).toBeInTheDocument();
      for (let tentativa = 0; tentativa < 3; tentativa++) await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
      expect(historico.queryByRole('heading', { name: 'Consulta confirmada' })).not.toBeInTheDocument();
      expect(screen.getByText('Consulta confirmada.')).toBeInTheDocument();
      expect(fetchMock.mock.calls.filter(([, init]) => init.method === 'POST')).toHaveLength(1);
      expect(chamadas(fetchMock, '/consultas/c1/historico')).toHaveLength(5);
      eventos.push(eventoHistorico(2, { evento: 'CONFIRMACAO', statusAnterior: 'AGENDADA', statusNovo: 'CONFIRMADA' }));
      await act(async () => { fireEvent.click(historico.getByRole('button', { name: 'Atualizar histórico' })); });
      expect(historico.getByRole('heading', { name: 'Consulta confirmada' })).toBeInTheDocument();
      expect(historico.queryByText(/histórico ainda pode estar incompleto/)).not.toBeInTheDocument();
      expect(fetchMock.mock.calls.filter(([, init]) => init.method === 'POST')).toHaveLength(1);
    } finally { vi.useRealTimers(); }
  });

  it.each(['outro-id', 'cursor-invalido', 'sequencia-repetida', 'pendencia-ausente', 'pendencia-invalida'])('resposta inválida (%s) vira erro isolado', async (caso) => {
    entrarComo(PAULO);
    mockApi({ historico: (id, parametros) => {
      const pagina = paginaHistorico(id, [eventoHistorico(1)], parametros);
      if (caso === 'outro-id') pagina.consultaId = 'c2';
      if (caso === 'cursor-invalido') { pagina.temMais = true; pagina.proximaSequencia = 0; }
      if (caso === 'sequencia-repetida') pagina.eventos.push(eventoHistorico(1, { id: 'duplicado' }));
      if (caso === 'pendencia-ausente') delete pagina.atualizacaoPendente;
      if (caso === 'pendencia-invalida') pagina.atualizacaoPendente = 'true';
      return pagina;
    } });
    renderAt('/consultas/c1');
    const historico = within(await screen.findByRole('region', { name: 'Histórico' }));
    expect(await historico.findByRole('alert')).toHaveTextContent('Não foi possível ler o histórico recebido.');
    expect(screen.getByRole('region', { name: 'Resumo da reserva' })).toBeInTheDocument();
  });

  it('campos legados ausentes e tipo desconhecido não derrubam o detalhe', async () => {
    entrarComo(PAULO);
    mockApi({ historico: [eventoHistorico(1, { evento: 'toString', ator: null, ocorridoEm: null, statusNovo: null })] });
    renderAt('/consultas/c1');
    const historico = within(await screen.findByRole('region', { name: 'Histórico' }));
    await historico.findByRole('heading', { name: 'Evento do histórico' });
    expect(historico.getByText('Responsável não identificado')).toBeInTheDocument();
    expect(historico.getByText('Data não informada')).toBeInTheDocument();
  });
});

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
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Seu login não está ligado a um cadastro de paciente. Procure a secretaria.',
    );
    expect(screen.queryByRole('button', { name: 'Tentar novamente' })).not.toBeInTheDocument();
  });

  it('permite recuperar o catálogo de médicos sem perder os filtros de consulta', async () => {
    entrarComo();
    let falhar = true;
    const fetchMock = mockApi({ medicos: () => falhar
      ? erro(503, 'Catálogo temporariamente indisponível.')
      : json([{ id: 'm1', nome: 'Ana Martins', ativo: true }]) });
    const user = userEvent.setup();
    renderAt('/consultas?medicoId=m1&status=AGENDADA');

    expect(await screen.findByRole('alert')).toHaveTextContent('Catálogo temporariamente indisponível.');
    expect(screen.getByLabelText('Médico')).toHaveValue('m1');
    expect(screen.getByLabelText('Médico')).toBeDisabled();
    expect(screen.getByLabelText('Status')).toHaveValue('AGENDADA');
    falhar = false;
    await user.click(screen.getByRole('button', { name: 'Tentar carregar médicos novamente' }));
    expect(await screen.findByRole('option', { name: 'Ana Martins' })).toBeInTheDocument();
    expect(screen.getByLabelText('Médico')).toHaveValue('m1');
    expect(screen.getByLabelText('Médico')).toBeEnabled();
    expect(chamadas(fetchMock, '/consultas/lista')).toHaveLength(1);
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

  it('preserva o motivo após falha temporária e envia o mesmo texto ao tentar novamente', async () => {
    entrarComo(PAULO);
    const motivo = 'Conflito de horário';
    const cancelar = vi.fn()
      .mockImplementationOnce(() => erro(503, 'Serviço indisponível'))
      .mockImplementationOnce(() => json(consulta('c1', { status: 'CANCELADA' })));
    mockApi({ cancelar });
    const user = userEvent.setup();
    renderAt('/consultas/c1');

    await user.click(await screen.findByRole('button', { name: 'Cancelar consulta' }));
    const dialog = within(screen.getByRole('dialog'));
    await user.type(dialog.getByLabelText('Motivo (opcional)'), motivo);
    await user.click(dialog.getByRole('button', { name: 'Confirmar cancelamento' }));

    expect(await dialog.findByRole('alert')).toHaveTextContent('Serviço indisponível');
    expect(dialog.getByLabelText('Motivo (opcional)')).toHaveValue(motivo);
    await user.click(dialog.getByRole('button', { name: 'Confirmar cancelamento' }));
    expect(await screen.findByText('Consulta cancelada. O horário foi liberado para outros pacientes.')).toBeInTheDocument();
    expect(cancelar.mock.calls).toEqual([['c1', { motivo }], ['c1', { motivo }]]);
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
