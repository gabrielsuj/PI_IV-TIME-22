import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import NovaConsultaPage from '../pages/NovaConsultaPage';
import { routes } from '../routes';
import { buscarVagas as buscarVagasApi, confirmarAgendamento as confirmarAgendamentoApi } from '../services/agendamentoService';
import { encerrarSessaoExpirada } from '../services/sessao';
import { entrarComo } from './sessaoTeste';

// Operações e respostas isoladas de teste, com casos usando o serviço HTTP real.
const horario = { inicio: '2026-12-01T12:00:00Z', fim: '2026-12-01T12:30:00Z' };
const outroHorario = { inicio: '2026-12-01T13:00:00Z', fim: '2026-12-01T13:30:00Z' };
const vagas = { horarios: [horario, outroHorario], fusoHorario: 'America/Sao_Paulo' };
const pacientes = [
  { id: 'p1', nome: 'Maria Teste', cpf: '01234567890' },
  { id: 'p2', nome: 'Paulo Teste', cpf: '52998224725' },
];
const json = (data, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } });

function deferred() {
  let resolve;
  const promise = new Promise((finish) => { resolve = finish; });
  return { promise, resolve };
}

function renderFluxo({ buscarVagas = vi.fn(async () => vagas), confirmarAgendamento = vi.fn(async () => ({ id: 'c1' })), perfil = 'SECRETARIA_ADMIN' } = {}) {
  entrarComo({ id: 'usuario-logado', nome: 'Usuário Teste', perfil });
  const fetchMock = vi.fn(async (url) => {
    const path = new URL(url).pathname;
    if (path === '/pacientes') return json(pacientes);
    if (path === '/especialidades') return json([{ id: 'e1', nome: 'Cardiologia', ativa: true }]);
    return json([
      { id: 'm1', nome: 'Ana Teste', crmNumero: '000123', crmUf: 'SP', especialidadeIds: ['e1'], ativo: true },
      { id: 'm2', nome: 'Bruno Teste', crmNumero: '004567', crmUf: 'SP', especialidadeIds: ['e1'], ativo: true },
    ]);
  });
  vi.stubGlobal('fetch', fetchMock);
  function configurar(rotas) {
    return rotas.map((rota) => ({
      ...rota,
      element: rota.path === 'nova-consulta' ? <NovaConsultaPage buscarVagas={buscarVagas} confirmarAgendamento={confirmarAgendamento} /> : rota.element,
      ...(rota.children ? { children: configurar(rota.children) } : {}),
    }));
  }
  const router = createMemoryRouter(configurar(routes), { initialEntries: ['/nova-consulta'] });
  render(<RouterProvider router={router} />);
  return { router, buscarVagas, confirmarAgendamento, fetchMock };
}

async function preencher(user, pessoal = false) {
  await screen.findByRole('option', { name: 'Cardiologia' });
  if (!pessoal) {
    await screen.findByRole('option', { name: 'Maria Teste — CPF 01234567890' });
    await user.selectOptions(screen.getByRole('combobox', { name: /^Paciente/ }), 'p1');
  }
  await user.selectOptions(screen.getByRole('combobox', { name: /Especialidade/ }), 'e1');
  await user.selectOptions(screen.getByRole('combobox', { name: /Médico/ }), 'm1');
  fireEvent.change(screen.getByLabelText(/Data da consulta/), { target: { value: '2026-12-01' } });
}

async function revisar(user) {
  await user.click(screen.getByRole('button', { name: 'Buscar horários' }));
  await user.click(await screen.findByRole('radio', { name: '09:00 às 09:30' }));
  await user.click(screen.getByRole('button', { name: 'Revisar agendamento' }));
  expect(screen.getByRole('region', { name: 'Revise sua consulta' })).toBeInTheDocument();
}

function conectarApi(fetchMock, { perfil = 'SECRETARIA_ADMIN', expirarEm = '/consultas', outraConta = false } = {}) {
  const catalogos = fetchMock.getMockImplementation();
  let expirou = false;
  fetchMock.mockImplementation(async (url, init) => {
    const path = new URL(url).pathname;
    if (path === '/auth/login') return json({ token: 'novo-token', usuario: {
      id: outraConta ? 'outra-conta' : 'usuario-logado', nome: 'Usuário Teste', perfil,
    } });
    if (path === '/auth/logout') return new Response(null, { status: 204 });
    if (!expirou && path === expirarEm) {
      expirou = true;
      return json({ erro: 'Sessão expirada.' }, 401);
    }
    if (path === '/vagas') return json(vagas);
    if (path === '/consultas') return json({ id: 'c1' }, 201);
    return catalogos(url, init);
  });
}

async function reautenticar(user) {
  await user.type(await screen.findByLabelText(/E-mail/), 'conta@example.com');
  await user.type(screen.getByLabelText(/Senha/), 'senha-de-teste');
  await user.click(screen.getByRole('button', { name: 'Entrar' }));
  await screen.findByRole('option', { name: 'Cardiologia' });
}

beforeEach(() => { sessionStorage.clear(); });

describe('fluxo integrado do frontend de agendamento', () => {
  it.each(['SECRETARIA_ADMIN', 'PACIENTE'])('recupera contexto após 401 para %s sem buscar vagas nem confirmar automaticamente', async (perfil) => {
    const { fetchMock } = renderFluxo({ perfil, buscarVagas: buscarVagasApi, confirmarAgendamento: confirmarAgendamentoApi });
    conectarApi(fetchMock, { perfil });
    const user = userEvent.setup();
    await preencher(user, perfil === 'PACIENTE');
    await revisar(user);
    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }));
    await screen.findByRole('heading', { name: 'Acesse sua conta' });
    expect(JSON.stringify({ ...sessionStorage, ...localStorage })).not.toMatch(/01234567890|2026-12-01/);
    await reautenticar(user);
    expect((await screen.findByText(/Dados do agendamento recuperados/)).closest('[role="status"]')).toBeInTheDocument();
    if (perfil === 'SECRETARIA_ADMIN') expect(await screen.findByRole('combobox', { name: /^Paciente/ })).toHaveValue('p1');
    expect(screen.getByRole('combobox', { name: /Especialidade/ })).toHaveValue('e1');
    expect(screen.getByRole('combobox', { name: /Médico/ })).toHaveValue('m1');
    expect(screen.getByLabelText(/Data da consulta/)).toHaveValue('2026-12-01');
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Confirmar agendamento' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Revisar agendamento' })).toBeDisabled();
    const confirmacoes = () => fetchMock.mock.calls.filter(([url, init]) => new URL(url).pathname === '/consultas' && init.method === 'POST');
    expect(confirmacoes()).toHaveLength(1);
    expect(fetchMock.mock.calls.filter(([url]) => new URL(url).pathname === '/vagas')).toHaveLength(1);
    await revisar(user);
    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }));
    await screen.findByRole('heading', { name: 'Agendamento realizado' });
    expect(confirmacoes()).toHaveLength(2);
    const enviado = JSON.parse(confirmacoes()[1][1].body);
    if (perfil === 'SECRETARIA_ADMIN') expect(enviado.pacienteId).toBe('p1');
    else expect(enviado).not.toHaveProperty('pacienteId');
    expect(screen.queryByText(/Dados do agendamento recuperados/)).not.toBeInTheDocument();
  });

  it('recupera os filtros quando a sessão expira na busca de vagas', async () => {
    const { fetchMock } = renderFluxo({ buscarVagas: buscarVagasApi, confirmarAgendamento: confirmarAgendamentoApi });
    conectarApi(fetchMock, { expirarEm: '/vagas' });
    const user = userEvent.setup();
    await preencher(user);
    await user.click(screen.getByRole('button', { name: 'Buscar horários' }));
    await reautenticar(user);
    expect(screen.getByLabelText(/Data da consulta/)).toHaveValue('2026-12-01');
    expect(screen.getByRole('combobox', { name: /^Paciente/ })).toHaveValue('p1');
    expect(screen.getByRole('button', { name: 'Buscar horários' })).toBeEnabled();
    expect(fetchMock.mock.calls.filter(([url]) => new URL(url).pathname === '/vagas')).toHaveLength(1);
    expect(fetchMock.mock.calls.filter(([url]) => new URL(url).pathname === '/consultas')).toHaveLength(0);
  });

  it('descarta o contexto da conta anterior ao entrar com outro usuário', async () => {
    const { fetchMock } = renderFluxo({ buscarVagas: buscarVagasApi, confirmarAgendamento: confirmarAgendamentoApi });
    conectarApi(fetchMock, { expirarEm: '/vagas', outraConta: true });
    const user = userEvent.setup();
    await preencher(user);
    await user.click(screen.getByRole('button', { name: 'Buscar horários' }));
    await reautenticar(user);
    expect(screen.getByLabelText(/Data da consulta/)).toHaveValue('');
    expect(screen.getByRole('combobox', { name: /^Paciente/ })).toHaveValue('');
    expect(screen.getByRole('combobox', { name: /Especialidade/ })).toHaveValue('');
    expect(screen.queryByText(/Dados do agendamento recuperados/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Buscar horários' })).toBeDisabled();
  });

  it('revalida o catálogo após login e descarta o médico que ficou inativo', async () => {
    const { fetchMock } = renderFluxo({ buscarVagas: buscarVagasApi, confirmarAgendamento: confirmarAgendamentoApi });
    conectarApi(fetchMock, { expirarEm: '/vagas' });
    const user = userEvent.setup();
    await preencher(user);
    await user.click(screen.getByRole('button', { name: 'Buscar horários' }));
    await screen.findByRole('heading', { name: 'Acesse sua conta' });
    const apiConectada = fetchMock.getMockImplementation();
    fetchMock.mockImplementation((url, init) => new URL(url).pathname === '/medicos'
      ? Promise.resolve(json([{ id: 'm1', nome: 'Ana Teste', especialidadeIds: ['e1'], ativo: false }])) : apiConectada(url, init));
    await reautenticar(user);
    expect(screen.getByRole('combobox', { name: /Médico/ })).toHaveValue('');
    expect(screen.getByRole('combobox', { name: /Especialidade/ })).toHaveValue('e1');
    expect(screen.getByLabelText(/Data da consulta/)).toHaveValue('2026-12-01');
    expect(screen.getByRole('button', { name: 'Buscar horários' })).toBeDisabled();
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
  });

  it('descarta o contexto ao sair voluntariamente da conta', async () => {
    const { fetchMock } = renderFluxo();
    conectarApi(fetchMock, { expirarEm: null });
    const user = userEvent.setup();
    await preencher(user);
    await user.click(screen.getByRole('button', { name: 'Conta de Usuário Teste' }));
    await user.click(screen.getByRole('button', { name: 'Sair' }));
    await reautenticar(user);
    expect(screen.getByLabelText(/Data da consulta/)).toHaveValue('');
    expect(screen.getByRole('combobox', { name: /^Paciente/ })).toHaveValue('');
    expect(screen.queryByText(/Dados do agendamento recuperados/)).not.toBeInTheDocument();
  });

  it('descarta o rascunho ao sair da página sem expiração de sessão', async () => {
    const { router } = renderFluxo();
    const user = userEvent.setup();
    await preencher(user);
    await act(async () => { await router.navigate('/perfis'); });
    await act(async () => { await router.navigate('/nova-consulta'); });
    await screen.findByRole('option', { name: 'Cardiologia' });
    expect(screen.getByLabelText(/Data da consulta/)).toHaveValue('');
    expect(screen.getByRole('combobox', { name: /^Paciente/ })).toHaveValue('');
    expect(screen.queryByText(/Dados do agendamento recuperados/)).not.toBeInTheDocument();
  });

  it('não recupera um agendamento já confirmado quando outra requisição expira a sessão', async () => {
    const { fetchMock } = renderFluxo({ buscarVagas: buscarVagasApi, confirmarAgendamento: confirmarAgendamentoApi });
    conectarApi(fetchMock, { expirarEm: null });
    const user = userEvent.setup();
    await preencher(user);
    await revisar(user);
    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }));
    await screen.findByRole('heading', { name: 'Agendamento realizado' });
    act(() => encerrarSessaoExpirada());
    await reautenticar(user);
    expect(screen.getByLabelText(/Data da consulta/)).toHaveValue('');
    expect(screen.getByRole('combobox', { name: /^Paciente/ })).toHaveValue('');
    expect(screen.queryByText(/Dados do agendamento recuperados/)).not.toBeInTheDocument();
  });

  it('secretaria busca, revisa e confirma somente após ação explícita, e pode iniciar outra consulta', async () => {
    const { buscarVagas, confirmarAgendamento } = renderFluxo();
    const user = userEvent.setup();
    expect(screen.getByRole('button', { name: 'Buscar horários' })).toBeDisabled();
    await preencher(user);
    expect(buscarVagas).not.toHaveBeenCalled();
    await revisar(user);
    expect(buscarVagas).toHaveBeenCalledExactlyOnceWith({ especialidadeId: 'e1', medicoId: 'm1', data: '2026-12-01' }, expect.objectContaining({ signal: expect.any(AbortSignal) }));
    expect(confirmarAgendamento).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Consulta agendada. Código da consulta: c1.');
    expect(confirmarAgendamento).toHaveBeenCalledExactlyOnceWith({ pacienteId: 'p1', especialidadeId: 'e1', medicoId: 'm1', ...horario });
    await user.click(screen.getByRole('button', { name: 'Nova consulta' }));
    expect(screen.getByLabelText(/Data da consulta/)).toHaveValue('');
    expect(screen.getByRole('combobox', { name: /^Paciente/ })).toHaveValue('');
    expect(screen.getByRole('button', { name: 'Buscar horários' })).toBeDisabled();
    expect(screen.queryByRole('heading', { name: 'Agendamento realizado' })).not.toBeInTheDocument();
  });

  it('paciente confirma para si sem enviar ID de usuário como pacienteId nem carregar outros pacientes', async () => {
    const { confirmarAgendamento, fetchMock } = renderFluxo({ perfil: 'PACIENTE' });
    const user = userEvent.setup();
    await preencher(user, true);
    await revisar(user);
    expect(screen.getByText('Usuário Teste (você)')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }));
    await screen.findByRole('heading', { name: 'Agendamento realizado' });
    expect(confirmarAgendamento).toHaveBeenCalledExactlyOnceWith({ especialidadeId: 'e1', medicoId: 'm1', ...horario });
    expect(fetchMock.mock.calls.some(([url]) => new URL(url).pathname === '/pacientes')).toBe(false);
  });

  it('impede envio duplo e bloqueia mudanças nos dados durante a confirmação', async () => {
    const pending = deferred();
    const confirmarAgendamento = vi.fn(() => pending.promise);
    renderFluxo({ confirmarAgendamento });
    const user = userEvent.setup();
    await preencher(user);
    await revisar(user);
    const form = screen.getByRole('button', { name: 'Confirmar agendamento' }).closest('form');
    act(() => { fireEvent.submit(form); fireEvent.submit(form); });
    expect(confirmarAgendamento).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'Confirmando…' })).toBeDisabled();
    for (const label of [/^Paciente/, /Especialidade/, /Médico/]) expect(screen.getByRole('combobox', { name: label })).toBeDisabled();
    expect(screen.getByLabelText(/Data da consulta/)).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Voltar para os horários' })).toBeDisabled();
    await act(async () => { pending.resolve({ id: 'c1' }); });
    expect(await screen.findByRole('heading', { name: 'Agendamento realizado' })).toBeInTheDocument();
    expect(confirmarAgendamento).toHaveBeenCalledTimes(1);
  });

  it.each([400, 403, 404, 500])('mantém os dados após erro %s de confirmação e só repete com novo comando do usuário', async (status) => {
    const confirmarAgendamento = vi.fn().mockRejectedValueOnce({ status, message: 'Falha ao confirmar.' }).mockResolvedValueOnce({ id: 'c1' });
    renderFluxo({ confirmarAgendamento });
    const user = userEvent.setup();
    await preencher(user);
    await revisar(user);
    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Falha ao confirmar.');
    expect(screen.getByText('09:00 às 09:30')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: /^Paciente/ })).toHaveValue('p1');
    expect(screen.getByLabelText(/Data da consulta/)).toHaveValue('2026-12-01');
    expect(confirmarAgendamento).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }));
    await screen.findByRole('heading', { name: 'Agendamento realizado' });
    expect(confirmarAgendamento).toHaveBeenCalledTimes(2);
  });

  it.each([
    ['rede', () => Promise.reject(new TypeError('Failed to fetch'))],
    ['timeout', (_url, init) => {
      vi.spyOn(init.signal, 'aborted', 'get').mockReturnValue(true);
      return Promise.reject(new DOMException('Tempo esgotado', 'TimeoutError'));
    }],
    ['leitura interrompida', () => Promise.resolve({
      status: 201,
      ok: true,
      text: () => Promise.reject(new TypeError('Conexão interrompida')),
    })],
    ['timeout na leitura', () => Promise.resolve({
      status: 201,
      ok: true,
      text: () => Promise.reject(new DOMException('Tempo esgotado', 'TimeoutError')),
    })],
  ])('orienta conferência após falha de %s no POST, sem reenviar nem perder os filtros', async (_caso, responder) => {
    const { fetchMock } = renderFluxo({ buscarVagas: buscarVagasApi, confirmarAgendamento: confirmarAgendamentoApi });
    const catalogos = fetchMock.getMockImplementation();
    fetchMock.mockImplementation((url, init) => {
      if (new URL(url).pathname === '/vagas') return Promise.resolve(json(vagas));
      if (init.method === 'POST') return responder(url, init);
      return catalogos(url, init);
    });
    const user = userEvent.setup();
    await preencher(user);
    await revisar(user);
    const confirmar = screen.getByRole('button', { name: 'Confirmar agendamento' });
    const form = confirmar.closest('form');
    await user.click(confirmar);
    const painel = await screen.findByRole('region', { name: 'Verifique o agendamento' });
    expect(painel).toHaveFocus();
    expect(within(painel).getByRole('alert')).toHaveTextContent('Confira o agendamento antes de tentar novamente.');
    expect(screen.queryByRole('button', { name: 'Confirmar agendamento' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Agendamento realizado' })).not.toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: /^Paciente/ })).toHaveValue('p1');
    expect(screen.getByRole('combobox', { name: /Especialidade/ })).toHaveValue('e1');
    expect(screen.getByRole('combobox', { name: /Médico/ })).toHaveValue('m1');
    expect(screen.getByLabelText(/Data da consulta/)).toHaveValue('2026-12-01');
    // Mesmo um evento atrasado do formulário anterior não reenvia o POST.
    fireEvent.submit(form);
    expect(fetchMock.mock.calls.filter(([, init]) => init.method === 'POST')).toHaveLength(1);
    expect(fetchMock.mock.calls.filter(([url]) => new URL(url).pathname === '/vagas')).toHaveLength(1);
  });

  it('recarrega vagas após 409, descarta o horário ocupado e preserva paciente e filtros', async () => {
    const buscarVagas = vi.fn().mockResolvedValueOnce(vagas).mockResolvedValueOnce({ ...vagas, horarios: [outroHorario] });
    const confirmarAgendamento = vi.fn().mockRejectedValueOnce({ status: 409 }).mockResolvedValueOnce({ id: 'c2' });
    renderFluxo({ buscarVagas, confirmarAgendamento });
    const user = userEvent.setup();
    await preencher(user);
    await revisar(user);
    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }));
    await screen.findByRole('radio', { name: '10:00 às 10:30' });
    expect(screen.getByRole('alert')).toHaveTextContent('Escolha outro horário na lista atualizada.');
    expect(screen.queryByRole('radio', { name: '09:00 às 09:30' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Revisar agendamento' })).toBeDisabled();
    expect(screen.getByRole('combobox', { name: /^Paciente/ })).toHaveValue('p1');
    expect(screen.getByRole('combobox', { name: /Médico/ })).toHaveValue('m1');
    expect(screen.getByLabelText(/Data da consulta/)).toHaveValue('2026-12-01');
    expect(buscarVagas).toHaveBeenCalledTimes(2);
    expect(confirmarAgendamento).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole('radio', { name: '10:00 às 10:30' }));
    await user.click(screen.getByRole('button', { name: 'Revisar agendamento' }));
    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }));
    await screen.findByRole('heading', { name: 'Agendamento realizado' });
    expect(confirmarAgendamento).toHaveBeenLastCalledWith(expect.objectContaining(outroHorario));
  });

  it('não anuncia sucesso nem permite reenviar quando a resposta não comprova confirmação', async () => {
    const confirmarAgendamento = vi.fn(async () => undefined);
    renderFluxo({ confirmarAgendamento });
    const user = userEvent.setup();
    await preencher(user);
    await revisar(user);
    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }));
    expect(await screen.findByRole('heading', { name: 'Verifique o agendamento' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Confirmar agendamento' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Agendamento realizado' })).not.toBeInTheDocument();
    expect(confirmarAgendamento).toHaveBeenCalledTimes(1);
  });

  it('descarta resposta atrasada de uma busca anterior após mudar a data', async () => {
    const antiga = deferred();
    const novos = { ...vagas, horarios: [{ inicio: '2026-12-02T13:00:00Z', fim: '2026-12-02T13:30:00Z' }] };
    const buscarVagas = vi.fn().mockReturnValueOnce(antiga.promise).mockResolvedValueOnce(novos);
    renderFluxo({ buscarVagas });
    const user = userEvent.setup();
    await preencher(user);
    await user.click(screen.getByRole('button', { name: 'Buscar horários' }));
    const signal = buscarVagas.mock.calls[0][1].signal;
    fireEvent.change(screen.getByLabelText(/Data da consulta/), { target: { value: '2026-12-02' } });
    expect(signal.aborted).toBe(true);
    await user.click(screen.getByRole('button', { name: 'Buscar horários' }));
    await user.click(await screen.findByRole('radio', { name: '10:00 às 10:30' }));
    await act(async () => { antiga.resolve(vagas); });
    expect(screen.getByRole('radio', { name: '10:00 às 10:30' })).toBeChecked();
    expect(screen.queryByRole('radio', { name: '09:00 às 09:30' })).not.toBeInTheDocument();
  });

  it.each(['paciente', 'medico', 'data'])('descarta seleção e revisão ao mudar %s', async (campo) => {
    const { buscarVagas } = renderFluxo();
    const user = userEvent.setup();
    await preencher(user);
    await revisar(user);
    if (campo === 'paciente') await user.selectOptions(screen.getByRole('combobox', { name: /^Paciente/ }), 'p2');
    else if (campo === 'medico') await user.selectOptions(screen.getByRole('combobox', { name: /Médico/ }), 'm2');
    else fireEvent.change(screen.getByLabelText(/Data da consulta/), { target: { value: '2026-12-02' } });
    expect(screen.queryByRole('region', { name: 'Revise sua consulta' })).not.toBeInTheDocument();
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Revisar agendamento' })).toBeDisabled();
    expect(buscarVagas).toHaveBeenCalledTimes(1);
  });

  it('distingue busca com falha e nova tentativa sem apagar os filtros', async () => {
    const buscarVagas = vi.fn().mockRejectedValueOnce({ status: 0, message: 'Falha de rede.' }).mockResolvedValueOnce(vagas);
    renderFluxo({ buscarVagas });
    const user = userEvent.setup();
    await preencher(user);
    await user.click(screen.getByRole('button', { name: 'Buscar horários' }));
    const horarios = within(screen.getByRole('region', { name: 'Horários disponíveis' }));
    expect(await horarios.findByRole('alert')).toHaveTextContent('Falha de rede.');
    expect(screen.queryByText(/Nenhum horário disponível/)).not.toBeInTheDocument();
    expect(screen.getByLabelText(/Data da consulta/)).toHaveValue('2026-12-01');
    await user.click(horarios.getByRole('button', { name: 'Tentar novamente' }));
    expect(await screen.findByRole('radio', { name: '09:00 às 09:30' })).toBeInTheDocument();
    expect(buscarVagas).toHaveBeenCalledTimes(2);
  });

  it('não oferece vagas quando a resposta pertence a outro dia', async () => {
    renderFluxo({ buscarVagas: vi.fn(async () => ({ ...vagas, horarios: [{ inicio: '2026-12-02T12:00:00Z', fim: '2026-12-02T12:30:00Z' }] })) });
    const user = userEvent.setup();
    await preencher(user);
    await user.click(screen.getByRole('button', { name: 'Buscar horários' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível ler os horários recebidos.');
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Revisar agendamento' })).toBeDisabled();
  });

  it.each([
    [{ ...vagas, horarios: [], semAgenda: true }, 'O médico ainda não possui agenda configurada.'],
    [{ ...vagas, horarios: [] }, 'Nenhum horário disponível nesta data.'],
  ])('distingue ausência de agenda de dia sem vagas (%j)', async (resposta, mensagem) => {
    const confirmarAgendamento = vi.fn();
    renderFluxo({ buscarVagas: vi.fn(async () => resposta), confirmarAgendamento });
    const user = userEvent.setup();
    await preencher(user);
    await user.click(screen.getByRole('button', { name: 'Buscar horários' }));
    expect(await screen.findByText(new RegExp(mensagem.replace('.', '\\.')))).toHaveAttribute('role', 'status');
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
    expect(confirmarAgendamento).not.toHaveBeenCalled();
  });

  it('mantém a confirmação desabilitada se apenas a busca de vagas estiver conectada', async () => {
    renderFluxo({ confirmarAgendamento: null });
    const user = userEvent.setup();
    await preencher(user);
    await revisar(user);
    expect(screen.getByRole('button', { name: 'Confirmar agendamento' })).toBeDisabled();
    expect(screen.queryByRole('heading', { name: 'Agendamento realizado' })).not.toBeInTheDocument();
  });

  it('ignora confirmação atrasada após sair da tela e não repete a operação', async () => {
    const pending = deferred();
    const confirmarAgendamento = vi.fn(() => pending.promise);
    const { router } = renderFluxo({ confirmarAgendamento });
    const user = userEvent.setup();
    await preencher(user);
    await revisar(user);
    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }));
    await act(async () => { await router.navigate('/perfis'); });
    await act(async () => { pending.resolve({ id: 'c1' }); });
    expect(screen.getByRole('heading', { name: 'Perfis de acesso' })).toBeInTheDocument();
    expect(screen.queryByText(/Consulta agendada/)).not.toBeInTheDocument();
    expect(confirmarAgendamento).toHaveBeenCalledTimes(1);
  });

  it('mantém a rota de produção sem vagas simuladas quando não há operações da API', async () => {
    const { fetchMock } = renderFluxo({ buscarVagas: null, confirmarAgendamento: null });
    const user = userEvent.setup();
    await preencher(user);
    expect(screen.queryByRole('button', { name: 'Buscar horários' })).not.toBeInTheDocument();
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Confirmar agendamento' })).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.every(([, init]) => init.method === 'GET')).toBe(true);
  });
});
