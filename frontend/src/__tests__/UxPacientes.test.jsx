import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { routes } from '../routes';
import { entrarComo, SECRETARIA_TESTE } from './sessaoTeste';
import { recuperarRascunho } from '../services/rascunhoPacientes';

const patient = { id: 'p1', nome: 'Ana Teste', cpf: '01234567890', email: 'ana@example.com' };
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

function renderAt(path = '/pacientes') {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(<RouterProvider router={router} />);
  return router;
}

async function preencher(user, dialog) {
  await user.type(within(dialog).getByLabelText(/Nome/), 'Ana Teste');
  await user.type(within(dialog).getByLabelText(/CPF/), '012.345.678-90');
  await user.type(within(dialog).getByLabelText(/E-mail/), 'ANA@example.com');
}

beforeEach(() => {
  sessionStorage.clear();
  entrarComo();
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value() { this.open = true; } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value() { this.open = false; } });
});

describe('UX de pacientes (#49)', () => {
  it.each([400, 409, 403, 500, 'rede'])('mantém os dados e o modal após erro %s, sem atribuir erro global a um campo', async (status) => {
    const fetchMock = vi.fn(async (_url, init) => {
      if (init.method === 'GET') return json([]);
      if (status === 'rede') throw new TypeError('Failed to fetch');
      return json({ erro: 'Não foi possível salvar este cadastro.' }, status);
    });
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderAt();
    await user.click(await screen.findByRole('button', { name: 'Novo paciente' }));
    const dialog = screen.getByRole('dialog');
    await preencher(user, dialog);
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }));
    const error = await within(dialog).findByRole('alert');
    expect(error).toHaveFocus();
    expect(within(dialog).getByLabelText(/Nome/)).toHaveValue('Ana Teste');
    expect(within(dialog).getByLabelText(/CPF/)).toHaveValue('012.345.678-90');
    expect(within(dialog).getByLabelText(/E-mail/)).toHaveValue('ANA@example.com');
    expect(within(dialog).getByLabelText(/CPF/)).not.toHaveAttribute('aria-invalid');
    expect(within(dialog).getByRole('button', { name: 'Salvar' })).toBeEnabled();
    expect(fetchMock.mock.calls.filter(([, init]) => init.method === 'POST')).toHaveLength(1);
  });

  it('indica CPF e e-mail inválidos, foca no primeiro erro e mantém o preenchimento', async () => {
    const fetchMock = vi.fn(async () => json([]));
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderAt();
    await user.click(screen.getByRole('button', { name: 'Novo paciente' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText(/Nome/), 'Ana');
    await user.type(within(dialog).getByLabelText(/CPF/), '11111111111');
    await user.type(within(dialog).getByLabelText(/E-mail/), 'email-invalido');
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }));
    expect(within(dialog).getByLabelText(/CPF/)).toHaveFocus();
    expect(within(dialog).getByLabelText(/CPF/)).toHaveAccessibleDescription('CPF inválido: confira os dígitos verificadores.');
    expect(within(dialog).getByLabelText(/E-mail/)).toHaveAccessibleDescription('E-mail inválido. Exemplo: nome@dominio.com.');
    expect(fetchMock.mock.calls.every(([, init]) => init.method === 'GET')).toBe(true);
  });

  it('aceita espaços nas extremidades e normaliza o e-mail como o backend', async () => {
    const fetchMock = vi.fn(async (_url, init) => init.method === 'POST' ? json(patient, 201) : json([]));
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderAt();
    await user.click(screen.getByRole('button', { name: 'Novo paciente' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText(/Nome/), '  Ana Teste  ');
    await user.type(within(dialog).getByLabelText(/CPF/), '01234567890');
    await user.type(within(dialog).getByLabelText(/E-mail/), '  ANA@example.com  ');
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }));
    expect(await screen.findByText('Cadastro realizado.')).toBeInTheDocument();
    expect(JSON.parse(fetchMock.mock.calls.find(([, init]) => init.method === 'POST')[1].body)).toEqual(patientPayload());
  });

  it('navega pelos campos com Tab/Shift+Tab, envia com Enter e normaliza sem perder zero', async () => {
    const fetchMock = vi.fn(async (_url, init) => init.method === 'POST' ? json(patient, 201) : json([]));
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderAt();
    const trigger = screen.getByRole('button', { name: 'Novo paciente' });
    await user.click(trigger);
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByLabelText(/Nome/)).toHaveFocus();
    await user.keyboard('Ana Teste{Tab}');
    expect(within(dialog).getByLabelText(/CPF/)).toHaveFocus();
    await user.keyboard('012.345.678-90{Tab}');
    expect(within(dialog).getByLabelText(/E-mail/)).toHaveFocus();
    await user.tab({ shift: true });
    expect(within(dialog).getByLabelText(/CPF/)).toHaveFocus();
    await user.tab();
    await user.keyboard('ANA@example.com{Enter}');
    expect(await screen.findByText('Cadastro realizado.')).toBeInTheDocument();
    expect(trigger).toHaveFocus();
    const [, init] = fetchMock.mock.calls.find(([, init]) => init.method === 'POST');
    expect(JSON.parse(init.body)).toEqual(patientPayload());
    expect(recuperarRascunho(SECRETARIA_TESTE.id, '/pacientes')).toBeNull();
  });

  it('bloqueia submissões repetidas e cancelamento enquanto salva', async () => {
    let finish;
    const saving = new Promise((resolve) => { finish = resolve; });
    const fetchMock = vi.fn(async (_url, init) => init.method === 'POST' ? saving : json([]));
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderAt();
    await user.click(screen.getByRole('button', { name: 'Novo paciente' }));
    const dialog = screen.getByRole('dialog');
    await preencher(user, dialog);
    const form = within(dialog).getByLabelText(/Nome/).closest('form');
    fireEvent.submit(form);
    fireEvent.submit(form);
    expect(fetchMock.mock.calls.filter(([, init]) => init.method === 'POST')).toHaveLength(1);
    expect(within(dialog).getByRole('button', { name: 'Salvando…' })).toBeDisabled();
    fireEvent(dialog, new Event('cancel', { cancelable: true }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    await act(async () => { finish(json(patient, 201)); });
    expect(await screen.findByText('Cadastro realizado.')).toBeInTheDocument();
  });

  it('fecha pelo evento nativo de Escape, devolve o foco e limpa o rascunho', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json([])));
    const user = userEvent.setup();
    renderAt();
    const trigger = screen.getByRole('button', { name: 'Novo paciente' });
    await user.click(trigger);
    await preencher(user, screen.getByRole('dialog'));
    fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
    await user.click(trigger);
    expect(within(screen.getByRole('dialog')).getByLabelText(/CPF/)).toHaveValue('');
  });

  it.each(['criacao', 'edicao'])('recupera %s após 401 e login do mesmo usuário, sem envio automático', async (modo) => {
    let expired = false;
    const fetchMock = vi.fn(async (url, init) => {
      if (url.endsWith('/auth/login')) return json({ token: 'novo-token', usuario: SECRETARIA_TESTE });
      if (init.method === 'GET') return json(modo === 'edicao' ? [patient] : []);
      if (!expired) { expired = true; return json({ erro: 'Sessão expirada.' }, 401); }
      return json(patient, 200);
    });
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderAt();
    if (modo === 'edicao') await user.click(await screen.findByRole('button', { name: 'Editar Ana Teste' }));
    else {
      await user.click(screen.getByRole('button', { name: 'Novo paciente' }));
      await preencher(user, screen.getByRole('dialog'));
    }
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Salvar' }));
    await screen.findByRole('heading', { name: 'Acesse sua conta' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    // Os campos do paciente não são persistidos junto à sessão nem à senha.
    expect(JSON.stringify({ ...sessionStorage, ...localStorage })).not.toContain('012.345.678-90');
    await login(user);
    const restored = await screen.findByRole('dialog');
    expect(within(restored).getByLabelText(/Nome/)).toHaveValue(patient.nome);
    expect(within(restored).getByText(/Rascunho recuperado/)).toBeInTheDocument();
    const writes = () => fetchMock.mock.calls.filter(([url, init]) => !url.includes('/auth/') && ['POST', 'PUT'].includes(init.method));
    expect(writes()).toHaveLength(1);
    await user.click(within(restored).getByRole('button', { name: 'Salvar' }));
    expect(await screen.findByText(modo === 'edicao' ? 'Cadastro atualizado.' : 'Cadastro realizado.')).toBeInTheDocument();
    expect(writes()).toHaveLength(2);
    expect(writes()[1][0]).toBe(`http://api.teste/pacientes${modo === 'edicao' ? '/p1' : ''}`);
  });

  it('descarta os dados ao reautenticar outra conta', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url, init) => {
      if (url.endsWith('/auth/login')) return json({ token: 'outro-token', usuario: { ...SECRETARIA_TESTE, id: 'outra-secretaria' } });
      if (init.method === 'GET') return json([]);
      return json({ erro: 'Sessão expirada.' }, 401);
    }));
    const user = userEvent.setup();
    renderAt();
    await user.click(screen.getByRole('button', { name: 'Novo paciente' }));
    await preencher(user, screen.getByRole('dialog'));
    await user.click(screen.getByRole('button', { name: 'Salvar' }));
    await screen.findByRole('heading', { name: 'Acesse sua conta' });
    await login(user);
    await screen.findByRole('button', { name: 'Novo paciente' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Novo paciente' }));
    expect(within(screen.getByRole('dialog')).getByLabelText(/CPF/)).toHaveValue('');
  });

  it('limpa rascunhos ao sair voluntariamente', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url) => url.endsWith('/auth/logout') ? new Response(null, { status: 204 }) : json([])));
    const user = userEvent.setup();
    const router = renderAt();
    await user.click(screen.getByRole('button', { name: 'Novo paciente' }));
    await preencher(user, screen.getByRole('dialog'));
    await act(async () => { await router.navigate('/perfis'); });
    await user.click(screen.getByRole('button', { name: 'Conta de Sofia Secretaria' }));
    await user.click(screen.getByRole('button', { name: 'Sair' }));
    await screen.findByRole('heading', { name: 'Acesse sua conta' });
    expect(recuperarRascunho(SECRETARIA_TESTE.id, '/pacientes')).toBeNull();
  });
});

describe('UX de acesso (#49)', () => {
  it('exibe erro de credencial sem apagar e-mail e permite novo envio por Enter', async () => {
    sessionStorage.clear();
    const fetchMock = vi.fn(async () => json({ erro: 'Senha incorreta.' }, 401));
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderAt('/login');
    await user.type(screen.getByLabelText(/E-mail/), 'secretaria@example.com');
    await user.type(screen.getByLabelText(/Senha/), 'senha-de-teste{Enter}');
    expect(await screen.findByRole('alert')).toHaveTextContent('Senha incorreta.');
    expect(screen.getByRole('alert')).toHaveFocus();
    expect(screen.getByLabelText(/E-mail/)).toHaveValue('secretaria@example.com');
    expect(screen.getByLabelText(/Senha/)).toHaveValue('senha-de-teste');
    expect(sessionStorage.length).toBe(0);
    expect(localStorage.length).toBe(0);
  });
});

async function login(user) {
  await user.type(screen.getByLabelText(/E-mail/), 'secretaria@example.com');
  await user.type(screen.getByLabelText(/Senha/), 'senha-de-teste{Enter}');
  await waitFor(() => expect(screen.queryByRole('heading', { name: 'Acesse sua conta' })).not.toBeInTheDocument());
}

function patientPayload() { return { nome: patient.nome, cpf: patient.cpf, email: patient.email }; }
