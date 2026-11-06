import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { routes } from '../routes';

const patient = { id: 'p1', nome: 'Ana Souza', cpf: 'paciente-1', email: 'ana@example.com', telefone: '11999990000', preferenciaContato: 'E-mail' };

function response(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function renderAt(path) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(<RouterProvider router={router} />);
  return router;
}

beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value() { this.open = true; } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value() { this.open = false; } });
  sessionStorage.clear();
});

describe('cadastros padronizados', () => {
  it('filtra pacientes e mantém a estrutura acessível da tabela', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response([patient])));
    const user = userEvent.setup();
    renderAt('/pacientes');

    const table = await screen.findByRole('table', { name: 'Pacientes cadastrados' });
    expect(await within(table).findByText('Ana Souza')).toBeInTheDocument();
    expect(within(table).getByRole('columnheader', { name: 'Ações' })).toBeInTheDocument();
    await user.type(screen.getByRole('searchbox'), 'sem correspondência');
    expect(screen.getByText('Nenhum registro encontrado.')).toBeInTheDocument();
    await user.clear(screen.getByRole('searchbox'));
    expect(within(table).getByText('Ana Souza')).toBeInTheDocument();
  });

  it('associa mensagens aos campos obrigatórios e não envia cadastro inválido', async () => {
    const fetchMock = vi.fn(async () => response([]));
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderAt('/pacientes');
    await screen.findByText('Nenhum registro encontrado.');
    const trigger = screen.getByRole('button', { name: 'Novo paciente' });
    await user.click(trigger);
    const dialog = screen.getByRole('dialog', { name: 'Novo paciente' });
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }));

    const name = within(dialog).getByRole('textbox', { name: /Nome/ });
    expect(name).toHaveFocus();
    expect(name).toHaveAttribute('aria-invalid', 'true');
    expect(name).toHaveAccessibleDescription('Preencha este campo.');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await user.click(within(dialog).getByRole('button', { name: 'Cancelar' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it('preserva os dados preenchidos quando o salvamento falha e permite tentar novamente', async () => {
    let fail = true;
    const fetchMock = vi.fn(async (_url, options) => options.method === 'POST'
      ? fail ? response({ message: 'Serviço indisponível.' }, 503) : response({ id: 'p2' }, 201)
      : response([]));
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderAt('/pacientes');
    await screen.findByText('Nenhum registro encontrado.');
    await user.click(screen.getByRole('button', { name: 'Novo paciente' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText(/Nome/), 'Beatriz Lima');
    await user.type(within(dialog).getByLabelText(/CPF ou identificador/), 'paciente-2');
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Serviço indisponível.');
    expect(within(dialog).getByLabelText(/Nome/)).toHaveValue('Beatriz Lima');
    fail = false;
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }));
    expect(await screen.findByText('Cadastro realizado.')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    const [, options] = fetchMock.mock.calls.find(([, init]) => init.method === 'POST');
    expect(JSON.parse(options.body)).toMatchObject({ nome: 'Beatriz Lima', cpf: 'paciente-2' });
  });

  it('envia a edição para o registro selecionado', async () => {
    const fetchMock = vi.fn(async (_url, options) => options.method === 'PUT' ? response({ ...patient, nome: 'Ana Lima' }) : response([patient]));
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderAt('/pacientes');
    await user.click(await screen.findByRole('button', { name: 'Editar Ana Souza' }));
    const dialog = screen.getByRole('dialog', { name: 'Editar paciente' });
    await user.clear(within(dialog).getByLabelText(/Nome/));
    await user.type(within(dialog).getByLabelText(/Nome/), 'Ana Lima');
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }));

    expect(await screen.findByText('Cadastro atualizado.')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith('http://api.teste/pacientes/p1', expect.objectContaining({ method: 'PUT', body: expect.stringContaining('Ana Lima') }));
  });

  it('oferece nova tentativa após falha no carregamento', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(response({ message: 'Falha.' }, 503)).mockImplementation(async () => response([patient]));
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderAt('/pacientes');
    await user.click(await screen.findByRole('button', { name: 'Tentar novamente' }));
    expect(await screen.findByText('Ana Souza')).toBeInTheDocument();
  });

  it('usa especialidades cadastradas no formulário de médicos e exibe o status', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url) => response(url.endsWith('/especialidades') ? [{ id: 'e1', nome: 'Cardiologia', ativa: true }] : [{ id: 'm1', nome: 'Maria Alves', crmNumero: '12345', crmUf: 'SP', especialidadeIds: ['e1'], ativo: false }])));
    const user = userEvent.setup();
    renderAt('/medicos');
    expect(await screen.findByText('Inativo')).toHaveClass('badge--neutral');
    await user.click(screen.getByRole('button', { name: 'Novo médico' }));
    expect(await screen.findByRole('checkbox', { name: 'Cardiologia' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: /Especialidades/ })).toBeInTheDocument();
  });

  it('preserva os zeros do CRM e envia vínculos e status conforme a modelagem', async () => {
    const fetchMock = vi.fn(async (url, options) => {
      if (options.method === 'POST') return response({ id: 'm2' }, 201);
      return response(url.endsWith('/especialidades') ? [{ id: 'e1', nome: 'Cardiologia', ativa: true }] : []);
    });
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderAt('/medicos');
    await screen.findByText('Nenhum registro encontrado.');
    await user.click(screen.getByRole('button', { name: 'Novo médico' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText(/Nome/), 'Maria Alves');
    await user.type(within(dialog).getByLabelText(/Número do CRM/), '000123');
    await user.click(await within(dialog).findByRole('checkbox', { name: 'Cardiologia' }));
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Status' }), 'false');
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }));
    expect(await screen.findByText('Cadastro realizado.')).toBeInTheDocument();
    const [, options] = fetchMock.mock.calls.find(([, init]) => init.method === 'POST');
    expect(JSON.parse(options.body)).toEqual({ nome: 'Maria Alves', crmNumero: '000123', crmUf: 'SP', especialidadeIds: ['e1'], ativo: false });
  });

  it('valida o código da especialidade antes de salvar', async () => {
    const fetchMock = vi.fn(async () => response([]));
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderAt('/especialidades');
    await screen.findByText('Nenhum registro encontrado.');
    await user.click(screen.getByRole('button', { name: 'Nova especialidade' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText(/Nome/), 'Clínica geral');
    await user.type(within(dialog).getByLabelText(/Código/), 'Clínica geral');
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }));
    expect(within(dialog).getByLabelText(/Código/)).toHaveAccessibleDescription('Use letras minúsculas, números e hífens.');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe('telas de acesso', () => {
  it('valida o login sem enviar credenciais incompletas', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderAt('/login');
    await user.click(screen.getByRole('button', { name: 'Entrar' }));
    expect(screen.getByLabelText(/E-mail/)).toHaveAccessibleDescription('Informe um e-mail válido.');
    expect(screen.getByLabelText(/Senha/)).toHaveAttribute('type', 'password');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('apresenta os três perfis previstos para a Sprint 1', () => {
    renderAt('/perfis');
    for (const name of ['Secretaria / Administrador', 'Médico', 'Paciente']) {
      expect(screen.getByRole('heading', { name, level: 2 })).toBeInTheDocument();
    }
  });
});
