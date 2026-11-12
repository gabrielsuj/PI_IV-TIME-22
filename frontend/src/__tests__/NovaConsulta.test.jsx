import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { routes } from '../routes';
import { entrarComo, SECRETARIA_TESTE } from './sessaoTeste';

const especialidades = [
  { id: 'e1', nome: 'Cardiologia', ativa: true },
  { id: 'e2', nome: 'Clínica Geral', ativa: true },
  { id: 'e3', nome: 'Dermatologia', ativa: false },
];
const medicos = [
  { id: 'm1', nome: 'Ana Teste', crmNumero: '000123', crmUf: 'SP', especialidadeIds: ['e1'], ativo: true },
  { id: 'm2', nome: 'Bruno Teste', crmNumero: '004567', crmUf: 'SP', especialidadeIds: ['e2'], ativo: true },
  { id: 'm3', nome: 'Inativo Teste', crmNumero: '1', crmUf: 'SP', especialidadeIds: ['e1'], ativo: false },
];
const pacientes = [
  { id: 'p1', nome: 'Maria Teste', cpf: '01234567890', email: 'maria@example.com' },
  { id: 'p2', nome: 'Maria Teste', cpf: '52998224725', email: 'maria2@example.com' },
];
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

function respostaCatalogo(url) {
  const path = new URL(url).pathname;
  if (path === '/health') return json({ status: 'ok' });
  if (path === '/pacientes') return json(pacientes);
  return json(path === '/especialidades' ? especialidades : medicos);
}

function renderAt(path = '/nova-consulta') {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(<RouterProvider router={router} />);
  return router;
}

function mockCatalogos() {
  const fetchMock = vi.fn(async (url) => respostaCatalogo(url));
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

beforeEach(() => {
  sessionStorage.clear();
  entrarComo();
});

describe('tela de nova consulta', () => {
  it.each(['SECRETARIA_ADMIN', 'PACIENTE'])('permite a escolha do atendimento para %s usando os catálogos reais', async (perfil) => {
    entrarComo({ id: 'u-teste', nome: 'Teste', perfil });
    const fetchMock = mockCatalogos();
    const user = userEvent.setup();
    renderAt();
    expect(screen.getByRole('heading', { name: 'Nova consulta' })).toBeInTheDocument();
    await screen.findByRole('option', { name: 'Cardiologia' });
    await user.selectOptions(screen.getByRole('combobox', { name: /Especialidade/ }), 'e1');
    expect(screen.queryByRole('option', { name: 'Dermatologia' })).not.toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /Inativo Teste/ })).not.toBeInTheDocument();
    await user.selectOptions(screen.getByRole('combobox', { name: /Médico/ }), 'm1');
    fireEvent.change(screen.getByLabelText(/Data da consulta/), { target: { value: '2026-12-01' } });
    expect(screen.getByLabelText(/Data da consulta/)).toHaveValue('2026-12-01');
    await user.selectOptions(screen.getByRole('combobox', { name: /Especialidade/ }), 'e2');
    expect(screen.getByRole('combobox', { name: /Médico/ })).toHaveValue('');
    expect(screen.getByLabelText(/Data da consulta/)).toHaveValue('2026-12-01');
    expect(fetchMock.mock.calls.map(([url]) => new URL(url).pathname).sort()).toEqual(
      perfil === 'SECRETARIA_ADMIN' ? ['/especialidades', '/medicos', '/pacientes'] : ['/especialidades', '/medicos']);
    if (perfil === 'PACIENTE') {
      expect(screen.getByText('Você está escolhendo um atendimento para você, Teste.')).toBeInTheDocument();
      expect(screen.queryByRole('combobox', { name: /^Paciente/ })).not.toBeInTheDocument();
    } else {
      await user.selectOptions(screen.getByRole('combobox', { name: /^Paciente/ }), 'p1');
      expect(screen.getByRole('combobox', { name: /^Paciente/ })).toHaveValue('p1');
    }
    expect(fetchMock.mock.calls.every(([, init]) => init.method === 'GET')).toBe(true);
  });

  it('bloqueia o médico sem carregar catálogos', () => {
    entrarComo({ id: 'u-medico', nome: 'Médico', perfil: 'MEDICO' });
    const fetchMock = mockCatalogos();
    renderAt();
    expect(screen.getByRole('heading', { name: 'Acesso não permitido' })).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('exige login e retorna para nova consulta após autenticar', async () => {
    sessionStorage.clear();
    vi.stubGlobal('fetch', vi.fn(async (url) => {
      if (url.endsWith('/auth/login')) return json({ token: 'novo-token', usuario: SECRETARIA_TESTE });
      return respostaCatalogo(url);
    }));
    const user = userEvent.setup();
    const router = renderAt();
    await user.type(await screen.findByLabelText(/E-mail/), 'secretaria@example.com');
    expect(router.state.location.pathname).toBe('/login');
    await user.type(screen.getByLabelText(/Senha/), 'senha-de-teste{Enter}');
    expect(await screen.findByRole('heading', { name: 'Nova consulta' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/nova-consulta');
    expect(await screen.findByRole('option', { name: 'Cardiologia' })).toBeInTheDocument();
  });

  it('volta ao login se a sessão expirar durante o carregamento', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ erro: 'Sessão expirada.' }, 401)));
    renderAt();
    expect(await screen.findByRole('heading', { name: 'Acesse sua conta' })).toBeInTheDocument();
    expect(await screen.findByText('Sua sessão expirou. Entre novamente para continuar.')).toBeInTheDocument();
  });

  it('mostra erro de carga e permite nova tentativa sem apresentar catálogo vazio', async () => {
    let fail = true;
    vi.stubGlobal('fetch', vi.fn(async (url) => fail ? json({ erro: 'Falha temporária.' }, 503)
      : respostaCatalogo(url)));
    const user = userEvent.setup();
    renderAt();
    const filtros = within(screen.getByRole('region', { name: 'Escolha o atendimento' }));
    expect(await filtros.findByRole('alert')).toHaveTextContent('Falha temporária.');
    expect(screen.queryByText('Nenhuma especialidade ativa encontrada.')).not.toBeInTheDocument();
    fail = false;
    await user.click(filtros.getByRole('button', { name: 'Tentar novamente' }));
    expect(await screen.findByRole('option', { name: 'Cardiologia' })).toBeInTheDocument();
    expect(filtros.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('cancela os carregamentos ao sair da tela', async () => {
    const signals = [];
    vi.stubGlobal('fetch', vi.fn((_url, { signal }) => {
      signals.push(signal);
      return new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }));
    }));
    const router = renderAt();
    expect(screen.getByText('Carregando especialidades e médicos…')).toBeInTheDocument();
    await act(async () => { await router.navigate('/perfis'); });
    expect(signals).toHaveLength(3);
    expect(signals.every((signal) => signal.aborted)).toBe(true);
    expect(screen.getByRole('heading', { name: 'Perfis de acesso' })).toBeInTheDocument();
  });

  it('busca pacientes por CPF e mantém seleção e atendimento em erro e nova tentativa', async () => {
    let fail = false;
    const fetchMock = vi.fn(async (url) => {
      const parsed = new URL(url);
      if (parsed.pathname === '/pacientes' && parsed.searchParams.get('busca')) {
        return fail ? json({ mensagem: 'Falha temporária nos pacientes.' }, 503) : json([pacientes[1]]);
      }
      return respostaCatalogo(url);
    });
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderAt();
    await screen.findByRole('option', { name: 'Maria Teste — CPF 01234567890' });
    await user.selectOptions(screen.getByRole('combobox', { name: /^Paciente/ }), 'p1');
    await screen.findByRole('option', { name: 'Cardiologia' });
    await user.selectOptions(screen.getByRole('combobox', { name: /Especialidade/ }), 'e1');
    await user.selectOptions(screen.getByRole('combobox', { name: /Médico/ }), 'm1');
    fireEvent.change(screen.getByLabelText(/Data da consulta/), { target: { value: '2026-12-01' } });
    const atendimento = within(screen.getByRole('region', { name: 'Quem será atendido' }));
    expect(atendimento.getByRole('option', { name: 'Maria Teste — CPF 01234567890' })).toBeInTheDocument();
    expect(atendimento.getByRole('option', { name: 'Maria Teste — CPF 52998224725' })).toBeInTheDocument();
    await user.type(atendimento.getByRole('searchbox'), '529.982.247-25');
    fail = true;
    await user.click(atendimento.getByRole('button', { name: 'Buscar paciente' }));
    expect(await atendimento.findByRole('alert')).toHaveTextContent('Falha temporária nos pacientes.');
    expect(atendimento.getByRole('combobox', { name: /^Paciente/ })).toHaveValue('p1');
    expect(atendimento.getByRole('combobox', { name: /^Paciente/ })).toBeDisabled();
    expect(screen.getByLabelText(/Data da consulta/)).toHaveValue('2026-12-01');
    expect(screen.getByRole('combobox', { name: /Médico/ })).toHaveValue('m1');
    fail = false;
    await user.click(atendimento.getByRole('button', { name: 'Tentar novamente' }));
    await waitFor(() => expect(atendimento.getByRole('combobox', { name: /^Paciente/ })).toBeEnabled());
    expect(atendimento.queryByRole('alert')).not.toBeInTheDocument();
    expect(atendimento.getByRole('combobox', { name: /^Paciente/ })).toHaveValue('p1');
    expect(atendimento.getByRole('searchbox')).toHaveValue('529.982.247-25');
    await user.selectOptions(atendimento.getByRole('combobox', { name: /^Paciente/ }), 'p2');
    expect(atendimento.getByRole('combobox', { name: /^Paciente/ })).toHaveValue('p2');
    expect(screen.getByRole('combobox', { name: /Especialidade/ })).toHaveValue('e1');
    const searches = fetchMock.mock.calls.filter(([url]) => new URL(url).pathname === '/pacientes');
    expect(searches).toHaveLength(3);
    expect(new URL(searches[2][0]).searchParams.get('busca')).toBe('529.982.247-25');
  });

  it('não perde o paciente selecionado quando uma nova busca não retorna resultados', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url) => {
      const parsed = new URL(url);
      return parsed.pathname === '/pacientes' && parsed.searchParams.get('busca') ? json([]) : respostaCatalogo(url);
    }));
    const user = userEvent.setup();
    renderAt();
    await screen.findByRole('option', { name: 'Maria Teste — CPF 01234567890' });
    await user.selectOptions(screen.getByRole('combobox', { name: /^Paciente/ }), 'p1');
    const atendimento = within(screen.getByRole('region', { name: 'Quem será atendido' }));
    await user.type(atendimento.getByRole('searchbox'), 'sem correspondência{Enter}');
    expect(await atendimento.findByText('Nenhum paciente encontrado para esta busca.')).toHaveAttribute('role', 'status');
    expect(atendimento.getByRole('combobox', { name: /^Paciente/ })).toHaveValue('p1');
    expect(atendimento.getByRole('option', { name: 'Maria Teste — CPF 01234567890' })).toBeInTheDocument();
    await user.selectOptions(atendimento.getByRole('combobox', { name: /^Paciente/ }), '');
    expect(atendimento.getByRole('combobox', { name: /^Paciente/ })).toHaveValue('');
  });

  it('orienta cadastrar um paciente quando a lista está vazia sem bloquear os catálogos', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url) => new URL(url).pathname === '/pacientes' ? json([]) : respostaCatalogo(url)));
    renderAt();
    const atendimento = within(screen.getByRole('region', { name: 'Quem será atendido' }));
    expect(await atendimento.findByText('Nenhum paciente encontrado.')).toHaveAttribute('role', 'status');
    expect(atendimento.getByRole('combobox', { name: /^Paciente/ })).toBeDisabled();
    expect(atendimento.getByRole('link', { name: 'Cadastrar paciente' })).toHaveAttribute('href', '/pacientes');
    expect(await screen.findByRole('option', { name: 'Cardiologia' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: /Especialidade/ })).toBeEnabled();
  });

  it.each(['SECRETARIA_ADMIN', 'PACIENTE', 'MEDICO'])('exibe o atalho inicial conforme o perfil %s', async (perfil) => {
    entrarComo({ id: 'u-teste', nome: 'Teste', perfil });
    mockCatalogos();
    const user = userEvent.setup();
    const router = renderAt('/');
    const main = within(screen.getByRole('main'));
    if (perfil === 'MEDICO') expect(main.queryByRole('link', { name: 'Nova consulta' })).not.toBeInTheDocument();
    else {
      await user.click(main.getByRole('link', { name: 'Nova consulta' }));
      expect(router.state.location.pathname).toBe('/nova-consulta');
      expect(await screen.findByRole('heading', { name: 'Nova consulta' })).toBeInTheDocument();
    }
  });
});
