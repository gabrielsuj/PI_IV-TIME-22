import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { routes } from '../routes';
import { formatarPeriodo, horaParaMinutos, minutosParaHora } from '../utils/horario';

const ANA = { id: 'm1', nome: 'Ana Martins', crmNumero: '000123', crmUf: 'SP', especialidadeIds: ['e1'], ativo: true };
const AGENDA_ANA = {
  id: 'a1', medicoId: 'm1', fusoHorario: 'America/Sao_Paulo', duracaoMinutos: 30,
  jornadas: [{ diaSemana: 1, faixas: [{ inicioMinuto: 480, fimMinuto: 720 }] }],
};

function response(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

/** Simula a API: cada chave é "MÉTODO /caminho"; o valor é uma função que recebe a requisição. */
function mockApi(rotas) {
  const fetchMock = vi.fn(async (url, init = {}) => {
    const { pathname } = new URL(url);
    const chave = `${init.method ?? 'GET'} ${pathname}`;
    const rota = rotas[chave];
    if (!rota) return response({ status: 404, erro: 'Rota não encontrada' }, 404);
    return rota({ url: new URL(url), body: init.body ? JSON.parse(init.body) : undefined });
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function chamadas(fetchMock, metodo, caminho) {
  return fetchMock.mock.calls
    .filter(([url, init = {}]) => (init.method ?? 'GET') === metodo && new URL(url).pathname === caminho)
    .map(([url, init]) => ({ url: new URL(url), body: init.body ? JSON.parse(init.body) : undefined }));
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

describe('conversões de horário', () => {
  it('converte entre minutos e horas', () => {
    expect(minutosParaHora(480)).toBe('08:00');
    expect(minutosParaHora(1020)).toBe('17:00');
    expect(horaParaMinutos('13:30')).toBe(810);
    expect(horaParaMinutos('00:00', { fim: true })).toBe(1440);
  });

  it('formata períodos de bloqueio', () => {
    expect(formatarPeriodo('2026-11-10T09:00', '2026-11-10T10:00')).toBe('10/11/2026, 09:00 às 10:00');
    expect(formatarPeriodo('2026-11-10T00:00', '2026-11-11T00:00')).toBe('10/11/2026 (dia inteiro)');
    expect(formatarPeriodo('2026-11-10T00:00', '2026-11-13T00:00')).toBe('10/11/2026 a 12/11/2026 (dias inteiros)');
  });
});

describe('tela de agenda', () => {
  it('pede para escolher um médico quando nenhum está selecionado', async () => {
    mockApi({ 'GET /medicos': () => response([ANA]) });
    renderAt('/agenda');
    expect(await screen.findByText('Selecione um médico para configurar a agenda.')).toBeInTheDocument();
    expect(await screen.findByRole('option', { name: /Ana Martins/ })).toBeInTheDocument();
  });

  it('carrega a jornada do médico em horas e salva em minutos', async () => {
    const fetchMock = mockApi({
      'GET /medicos': () => response([ANA]),
      'GET /agendas': () => response(AGENDA_ANA),
      'GET /bloqueios': () => response([]),
      'PUT /agendas': ({ body }) => response({ ...AGENDA_ANA, ...body }),
    });
    const user = userEvent.setup();
    renderAt('/agenda?medicoId=m1');

    const segunda = await screen.findByRole('checkbox', { name: 'Segunda-feira' });
    expect(segunda).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Terça-feira' })).not.toBeChecked();
    expect(screen.getByLabelText('Início')).toHaveValue('08:00');
    expect(screen.getByLabelText('Fim')).toHaveValue('12:00');

    await user.click(screen.getByRole('checkbox', { name: 'Quarta-feira' }));
    await user.click(screen.getByRole('button', { name: 'Salvar jornada' }));

    expect(await screen.findByText('Agenda salva.')).toBeInTheDocument();
    const [put] = chamadas(fetchMock, 'PUT', '/agendas');
    expect(put.url.searchParams.get('medicoId')).toBe('m1');
    expect(put.body).toEqual({
      duracaoMinutos: 30,
      jornadas: [
        { diaSemana: 1, faixas: [{ inicioMinuto: 480, fimMinuto: 720 }] },
        { diaSemana: 3, faixas: [{ inicioMinuto: 480, fimMinuto: 720 }] },
      ],
    });
  });

  it('mostra a mensagem de erro devolvida pela API', async () => {
    mockApi({
      'GET /medicos': () => response([ANA]),
      'GET /agendas': () => response(AGENDA_ANA),
      'GET /bloqueios': () => response([]),
      'PUT /agendas': () => response({ status: 400, erro: 'Faixas sobrepostas no dia 1' }, 400),
    });
    const user = userEvent.setup();
    renderAt('/agenda?medicoId=m1');
    await user.click(await screen.findByRole('button', { name: 'Salvar jornada' }));
    expect(await screen.findByText('Faixas sobrepostas no dia 1')).toBeInTheDocument();
  });

  it('abre o formulário vazio quando o médico ainda não tem agenda', async () => {
    mockApi({
      'GET /medicos': () => response([ANA]),
      'GET /agendas': () => response({ status: 404, erro: 'Agenda não configurada para este médico' }, 404),
      'GET /bloqueios': () => response([]),
    });
    renderAt('/agenda?medicoId=m1');
    expect(await screen.findByText(/ainda não tem agenda/)).toBeInTheDocument();
    for (const dia of ['Segunda-feira', 'Domingo']) expect(screen.getByRole('checkbox', { name: dia })).not.toBeChecked();
  });

  it('continua funcionando quando a lista de médicos não está disponível', async () => {
    mockApi({
      'GET /agendas': () => response(AGENDA_ANA),
      'GET /bloqueios': () => response([]),
    });
    renderAt('/agenda?medicoId=m1');
    expect(await screen.findByText(/Não foi possível carregar a lista de médicos/)).toBeInTheDocument();
    expect(await screen.findByRole('checkbox', { name: 'Segunda-feira' })).toBeChecked();
  });

  it('cadastra um bloqueio de dia inteiro e remove um bloqueio existente', async () => {
    let bloqueios = [{ id: 'b1', medicoId: 'm1', inicio: '2026-11-10T09:00', fim: '2026-11-10T10:00', motivo: 'Congresso' }];
    const fetchMock = mockApi({
      'GET /medicos': () => response([ANA]),
      'GET /agendas': () => response(AGENDA_ANA),
      'GET /bloqueios': () => response(bloqueios),
      'POST /bloqueios': ({ body }) => {
        const novo = { id: 'b2', ...body };
        bloqueios = [...bloqueios, novo];
        return response(novo, 201);
      },
      'DELETE /bloqueios': ({ url }) => {
        bloqueios = bloqueios.filter((b) => b.id !== url.searchParams.get('id'));
        return response({ removido: true });
      },
    });
    const user = userEvent.setup();
    renderAt('/agenda?medicoId=m1');

    const tabela = await screen.findByRole('table', { name: 'Próximos bloqueios' });
    expect(await within(tabela).findByText('10/11/2026, 09:00 às 10:00')).toBeInTheDocument();

    await user.type(screen.getByLabelText(/Data de início/), '2026-11-20');
    await user.click(screen.getByRole('checkbox', { name: 'Dia inteiro' }));
    await user.type(screen.getByLabelText('Motivo (opcional)'), 'Feriado');
    await user.click(screen.getByRole('button', { name: 'Adicionar bloqueio' }));

    expect(await screen.findByText('Bloqueio cadastrado.')).toBeInTheDocument();
    const [post] = chamadas(fetchMock, 'POST', '/bloqueios');
    expect(post.body).toEqual({ medicoId: 'm1', inicio: '2026-11-20T00:00', fim: '2026-11-21T00:00', motivo: 'Feriado' });
    expect(await within(tabela).findByText('20/11/2026 (dia inteiro)')).toBeInTheDocument();

    await user.click(within(tabela).getByRole('button', { name: 'Remover bloqueio de 10/11/2026, 09:00 às 10:00' }));
    const dialogo = screen.getByRole('dialog', { name: 'Remover bloqueio?' });
    await user.click(within(dialogo).getByRole('button', { name: 'Remover' }));
    expect(await screen.findByText('Bloqueio removido.')).toBeInTheDocument();
    expect(chamadas(fetchMock, 'DELETE', '/bloqueios')[0].url.searchParams.get('id')).toBe('b1');
    expect(within(tabela).queryByText('10/11/2026, 09:00 às 10:00')).not.toBeInTheDocument();
  });

  it('recusa bloqueio sem data de início sem chamar a API', async () => {
    const fetchMock = mockApi({
      'GET /medicos': () => response([ANA]),
      'GET /agendas': () => response(AGENDA_ANA),
      'GET /bloqueios': () => response([]),
    });
    const user = userEvent.setup();
    renderAt('/agenda?medicoId=m1');
    await user.click(await screen.findByRole('button', { name: 'Adicionar bloqueio' }));
    expect(await screen.findByText('Informe a data de início.')).toBeInTheDocument();
    expect(chamadas(fetchMock, 'POST', '/bloqueios')).toHaveLength(0);
  });
});
