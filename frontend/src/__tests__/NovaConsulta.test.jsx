import { act, fireEvent, render, screen, within } from '@testing-library/react';
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
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

function renderAt(path = '/nova-consulta') {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(<RouterProvider router={router} />);
  return router;
}

function mockCatalogos() {
  const fetchMock = vi.fn(async (url) => {
    if (url.endsWith('/health')) return json({ status: 'ok' });
    return json(url.endsWith('/especialidades') ? especialidades : medicos);
  });
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
    await user.selectOptions(await screen.findByRole('combobox', { name: /Especialidade/ }), 'e1');
    expect(screen.queryByRole('option', { name: 'Dermatologia' })).not.toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /Inativo Teste/ })).not.toBeInTheDocument();
    await user.selectOptions(screen.getByRole('combobox', { name: /Médico/ }), 'm1');
    fireEvent.change(screen.getByLabelText(/Data da consulta/), { target: { value: '2026-12-01' } });
    expect(screen.getByLabelText(/Data da consulta/)).toHaveValue('2026-12-01');
    await user.selectOptions(screen.getByRole('combobox', { name: /Especialidade/ }), 'e2');
    expect(screen.getByRole('combobox', { name: /Médico/ })).toHaveValue('');
    expect(screen.getByLabelText(/Data da consulta/)).toHaveValue('2026-12-01');
    expect(fetchMock.mock.calls.map(([url]) => new URL(url).pathname).sort()).toEqual(['/especialidades', '/medicos']);
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
      return json(url.endsWith('/especialidades') ? especialidades : medicos);
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
      : json(url.endsWith('/especialidades') ? especialidades : medicos)));
    const user = userEvent.setup();
    renderAt();
    expect(await screen.findByRole('alert')).toHaveTextContent('Falha temporária.');
    expect(screen.queryByText('Nenhuma especialidade ativa encontrada.')).not.toBeInTheDocument();
    fail = false;
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));
    expect(await screen.findByRole('option', { name: 'Cardiologia' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('cancela os carregamentos ao sair da tela', async () => {
    const signals = [];
    vi.stubGlobal('fetch', vi.fn((_url, { signal }) => {
      signals.push(signal);
      return new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }));
    }));
    const router = renderAt();
    expect(screen.getByRole('status')).toHaveTextContent('Carregando especialidades');
    await act(async () => { await router.navigate('/perfis'); });
    expect(signals).toHaveLength(2);
    expect(signals.every((signal) => signal.aborted)).toBe(true);
    expect(screen.getByRole('heading', { name: 'Perfis de acesso' })).toBeInTheDocument();
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
