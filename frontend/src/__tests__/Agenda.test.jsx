/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387

Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { routes } from '../routes';
import { entrarComo } from './sessaoTeste';
import { formatarPeriodo, horaParaMinutos, minutosParaHora } from '../utils/horario';

const ANA = { id: 'm1', nome: 'Ana Martins', crmNumero: '000123', crmUf: 'SP', especialidadeIds: ['e1'], ativo: true };
const AGENDA_ANA = {
  id: 'a1', medicoId: 'm1', fusoHorario: 'America/Sao_Paulo', duracaoMinutos: 30,
  jornadas: [{ diaSemana: 1, faixas: [{ inicioMinuto: 480, fimMinuto: 720 }] }],
};

function response(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

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
  entrarComo();
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
  it.each([['/agenda', 'Minha agenda'], ['/calendario', 'Meu calendário']])(
    'médico abre somente a própria agenda em %s', async (rota, titulo) => {
      entrarComo({ id: 'u-medica', nome: 'Ana Martins', perfil: 'MEDICO' });
      const fetchMock = mockApi({
        'GET /agendas': () => response(AGENDA_ANA),
        'GET /bloqueios': () => response([]),
        'GET /consultas': () => response([]),
      });
      renderAt(rota + '?medicoId=outro');
      expect(await screen.findByRole('heading', { name: titulo })).toBeInTheDocument();
      expect(screen.queryByRole('combobox', { name: 'Médico' })).not.toBeInTheDocument();
      expect(chamadas(fetchMock, 'GET', '/medicos')).toHaveLength(0);
      const rotas = rota === '/agenda' ? ['/agendas', '/bloqueios'] : ['/agendas', '/bloqueios', '/consultas'];
      for (const caminho of rotas) {
        await waitFor(() => expect(chamadas(fetchMock, 'GET', caminho).length).toBeGreaterThan(0));
        expect(chamadas(fetchMock, 'GET', caminho).every(c => c.url.searchParams.get('medicoId') === 'meu')).toBe(true);
      }
    }
  );

  it('pede para escolher um médico quando nenhum está selecionado', async () => {
    mockApi({ 'GET /medicos': () => response([ANA]) });
    renderAt('/agenda');
    expect(await screen.findByText('Selecione um médico para configurar a agenda.')).toBeInTheDocument();
    expect(await screen.findByRole('option', { name: /Ana Martins/ })).toBeInTheDocument();
  });

  it('não exibe formulários nem atalho do calendário para médico inativo', async () => {
    mockApi({
      'GET /medicos': () => response([ANA, { ...ANA, id: 'inativo', nome: 'Médico Inativo', ativo: false }]),
      'GET /agendas': () => response(AGENDA_ANA),
      'GET /bloqueios': () => response([]),
    });
    renderAt('/agenda?medicoId=inativo');

    expect(await screen.findByText('O médico selecionado está indisponível. Selecione outro médico para continuar.')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Jornada semanal' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Bloqueios' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Ver horários no calendário' })).not.toBeInTheDocument();
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

  it('recarrega a lista de médicos sem perder a seleção nem a jornada em edição', async () => {
    const medicos = vi.fn()
      .mockReturnValueOnce(response({ erro: 'Serviço indisponível' }, 503))
      .mockImplementation(() => response([ANA]));
    mockApi({
      'GET /medicos': medicos,
      'GET /agendas': () => response(AGENDA_ANA),
      'GET /bloqueios': () => response([]),
    });
    const user = userEvent.setup();
    renderAt('/agenda?medicoId=m1');

    const erro = await screen.findByRole('alert');
    expect(erro).toHaveTextContent('Não foi possível carregar a lista de médicos.');
    await user.click(await screen.findByRole('checkbox', { name: 'Quarta-feira' }));
    await user.click(within(erro).getByRole('button', { name: 'Tentar novamente' }));
    expect(await screen.findByRole('option', { name: /Ana Martins/ })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Médico' })).toHaveValue('m1');
    expect(screen.getByRole('checkbox', { name: 'Quarta-feira' })).toBeChecked();
    expect(medicos).toHaveBeenCalledTimes(2);
  });

  it.each(['/agenda', '/calendario'])('distingue carregamento e ausência de médicos ativos em %s', async (rota) => {
    let concluir;
    const pendente = new Promise((resolve) => { concluir = resolve; });
    mockApi({ 'GET /medicos': () => pendente });
    renderAt(rota);

    expect(screen.getByText('Carregando lista de médicos…')).toBeInTheDocument();
    expect(screen.queryByText('Nenhum médico ativo disponível para seleção.')).not.toBeInTheDocument();
    expect(screen.queryByText(/^Selecione um médico para/)).not.toBeInTheDocument();

    concluir(response([{ ...ANA, ativo: false }]));
    expect(await screen.findByText('Nenhum médico ativo disponível para seleção.')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Médico' })).toBeDisabled();
    expect(screen.queryByText('Carregando lista de médicos…')).not.toBeInTheDocument();
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
    expect(screen.getByLabelText(/Data de início/)).toHaveFocus();
    expect(screen.getByLabelText(/Data de início/)).toHaveAttribute('aria-invalid', 'true');
    expect(chamadas(fetchMock, 'POST', '/bloqueios')).toHaveLength(0);
  });

  it('orienta a configuração sem jornada e mostra o resumo antes de salvar', async () => {
    mockApi({
      'GET /medicos': () => response([ANA]),
      'GET /agendas': () => response({ status: 404, erro: 'Agenda não configurada para este médico' }, 404),
      'GET /bloqueios': () => response([]),
    });
    const user = userEvent.setup();
    renderAt('/agenda?medicoId=m1');

    expect(await screen.findByText(/Salve a jornada para liberar os horários/)).toBeInTheDocument();
    const resumo = screen.getByRole('region', { name: 'Resumo da jornada' });
    expect(resumo).toHaveTextContent('Nenhum dia de atendimento selecionado');
    expect(screen.getByRole('link', { name: 'Ver horários no calendário' })).toHaveAttribute('href', '/calendario?medicoId=m1');

    await user.click(screen.getByRole('checkbox', { name: 'Quarta-feira' }));
    expect(resumo).toHaveTextContent('Quarta-feira: 08:00 às 12:00');
    expect(resumo).toHaveTextContent('Consultas de 30 minutos');
    expect(resumo).toHaveTextContent('bloqueios e as consultas já marcadas');
  });

  it('valida a jornada por campo e foca a faixa com duração insuficiente', async () => {
    const fetchMock = mockApi({
      'GET /medicos': () => response([ANA]),
      'GET /agendas': () => response(AGENDA_ANA),
      'GET /bloqueios': () => response([]),
    });
    const user = userEvent.setup();
    renderAt('/agenda?medicoId=m1');
    const fim = await screen.findByLabelText('Fim');
    await user.clear(fim);
    await user.type(fim, '08:15');
    await user.click(screen.getByRole('button', { name: 'Salvar jornada' }));

    expect(await screen.findByText('Reserve pelo menos 30 minutos para uma consulta.')).toBeInTheDocument();
    expect(fim).toHaveFocus();
    expect(fim).toHaveAccessibleDescription('Reserve pelo menos 30 minutos para uma consulta.');
    expect(chamadas(fetchMock, 'PUT', '/agendas')).toHaveLength(0);
    expect(fim).toHaveValue('08:15');
  });

  it('valida datas invertidas no bloqueio sem perder os valores', async () => {
    const fetchMock = mockApi({
      'GET /medicos': () => response([ANA]),
      'GET /agendas': () => response(AGENDA_ANA),
      'GET /bloqueios': () => response([]),
    });
    const user = userEvent.setup();
    renderAt('/agenda?medicoId=m1');
    await user.type(await screen.findByLabelText(/Data de início/), '2026-12-10');
    await user.type(screen.getByLabelText('Data de fim'), '2026-12-09');
    await user.type(screen.getByLabelText('Motivo (opcional)'), 'Congresso');
    await user.click(screen.getByRole('button', { name: 'Adicionar bloqueio' }));

    const fim = screen.getByLabelText('Data de fim');
    expect(await screen.findByText('A data de fim deve ser igual ou posterior à data de início.')).toBeInTheDocument();
    expect(fim).toHaveFocus();
    expect(fim).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Motivo (opcional)')).toHaveValue('Congresso');
    expect(chamadas(fetchMock, 'POST', '/bloqueios')).toHaveLength(0);
  });

  it('confere dias inteiros inclusivos e preserva o rascunho quando a API falha', async () => {
    mockApi({
      'GET /medicos': () => response([ANA]),
      'GET /agendas': () => response(AGENDA_ANA),
      'GET /bloqueios': () => response([]),
      'POST /bloqueios': () => response({ erro: 'Serviço indisponível' }, 503),
    });
    const user = userEvent.setup();
    renderAt('/agenda?medicoId=m1');
    await user.type(await screen.findByLabelText(/Data de início/), '2026-12-10');
    await user.type(screen.getByLabelText('Data de fim'), '2026-12-12');
    await user.click(screen.getByRole('checkbox', { name: 'Dia inteiro' }));
    await user.type(screen.getByLabelText('Motivo (opcional)'), 'Férias');

    const resumo = screen.getByRole('region', { name: 'Resumo do novo bloqueio' });
    expect(resumo).toHaveTextContent('10/12/2026 a 12/12/2026 (dias inteiros)');
    expect(resumo).toHaveTextContent('Férias');
    await user.click(screen.getByRole('button', { name: 'Adicionar bloqueio' }));

    expect(await screen.findByText('Serviço indisponível')).toBeInTheDocument();
    expect(screen.getByLabelText(/Data de início/)).toHaveValue('2026-12-10');
    expect(screen.getByLabelText('Data de fim')).toHaveValue('2026-12-12');
    expect(screen.getByLabelText('Motivo (opcional)')).toHaveValue('Férias');
    expect(screen.getByRole('checkbox', { name: 'Dia inteiro' })).toBeChecked();
    expect(resumo).toHaveTextContent('10/12/2026 a 12/12/2026 (dias inteiros)');
  });

  it('pede confirmação ao trocar de médico e mantém o formulário ao continuar editando', async () => {
    const OUTRA = { ...ANA, id: 'm2', nome: 'Beatriz Souza', crmNumero: '456789' };
    mockApi({
      'GET /medicos': () => response([ANA, OUTRA]),
      'GET /agendas': () => response(AGENDA_ANA),
      'GET /bloqueios': () => response([]),
    });
    const user = userEvent.setup();
    const router = renderAt('/agenda?medicoId=m1');
    await user.click(await screen.findByRole('checkbox', { name: 'Quarta-feira' }));
    await user.selectOptions(screen.getByRole('combobox', { name: 'Médico' }), 'm2');

    const dialogo = screen.getByRole('dialog', { name: 'Sair sem salvar as alterações?' });
    expect(router.state.location.search).toBe('?medicoId=m1');
    await user.click(within(dialogo).getByRole('button', { name: 'Continuar editando' }));
    expect(screen.getByRole('checkbox', { name: 'Quarta-feira' })).toBeChecked();
    expect(screen.getByRole('combobox', { name: 'Médico' })).toHaveValue('m1');

    await user.selectOptions(screen.getByRole('combobox', { name: 'Médico' }), 'm2');
    await user.click(screen.getByRole('button', { name: 'Descartar e continuar' }));
    expect(router.state.location.search).toBe('?medicoId=m2');
    expect(await screen.findByRole('checkbox', { name: 'Quarta-feira' })).not.toBeChecked();
  });

  it.each(['Ver horários no calendário', 'Consultas'])('preserva o novo bloqueio ao cancelar a navegação por %s', async (link) => {
    mockApi({
      'GET /medicos': () => response([ANA]),
      'GET /agendas': () => response(AGENDA_ANA),
      'GET /bloqueios': () => response([]),
    });
    const user = userEvent.setup();
    const router = renderAt('/agenda?medicoId=m1');
    await user.type(await screen.findByLabelText(/Data de início/), '2026-12-10');
    await user.click(screen.getByRole('link', { name: link, exact: true }));
    await user.click(screen.getByRole('button', { name: 'Continuar editando' }));

    expect(router.state.location.pathname).toBe('/agenda');
    expect(screen.getByLabelText(/Data de início/)).toHaveValue('2026-12-10');
  });
});
