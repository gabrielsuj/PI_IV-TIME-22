import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { routes } from '../routes';
import { classificarHorario, diasDaSemana, horariosDoDia, rotuloDia, somarDias } from '../utils/calendario';
import { entrarComo } from './sessaoTeste';

const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

// Ana: segunda 08:00–09:00 (30 min) e quarta 08:00–08:30.
const AGENDA = {
  id: 'a1', medicoId: 'm1', fusoHorario: 'America/Sao_Paulo', duracaoMinutos: 30,
  jornadas: [
    { diaSemana: 1, faixas: [{ inicioMinuto: 480, fimMinuto: 540 }] },
    { diaSemana: 3, faixas: [{ inicioMinuto: 480, fimMinuto: 510 }] },
  ],
};

function mockApi({ agenda = AGENDA, consultas = [], bloqueios = [] } = {}) {
  const fetchMock = vi.fn(async (url) => {
    const { pathname } = new URL(url);
    if (pathname === '/medicos') return json([{ id: 'm1', nome: 'Ana Martins', crmNumero: '123', crmUf: 'SP', ativo: true }]);
    if (pathname === '/agendas') return agenda ? json(agenda) : json({ status: 404, erro: 'Agenda não configurada' }, 404);
    if (pathname === '/bloqueios') return json(bloqueios);
    if (pathname === '/consultas') return json(consultas);
    return json({}, 404);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

const chamadasPara = (fetchMock, caminho) => fetchMock.mock.calls.map(([url]) => new URL(url))
  .filter((url) => url.pathname === caminho);

function renderAt(path) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(<RouterProvider router={router} />);
  return router;
}

beforeEach(() => {
  sessionStorage.clear();
  entrarComo();
});

describe('utilitários do calendário', () => {
  it('monta a semana de segunda a domingo e soma dias', () => {
    expect(diasDaSemana('2026-11-12')).toEqual(['2026-11-09', '2026-11-10', '2026-11-11', '2026-11-12', '2026-11-13', '2026-11-14', '2026-11-15']);
    expect(somarDias('2026-11-30', 1)).toBe('2026-12-01');
    expect(rotuloDia('2026-11-12')).toBe('qui 12/11');
  });

  it('gera os horários do dia pela jornada e pela duração', () => {
    expect(horariosDoDia(AGENDA, '2026-11-09').map((h) => [h.inicio, h.fim])).toEqual([
      ['2026-11-09T08:00', '2026-11-09T08:30'],
      ['2026-11-09T08:30', '2026-11-09T09:00'],
    ]);
    expect(horariosDoDia(AGENDA, '2026-11-10')).toEqual([]);
  });
});

const HORARIO = { inicio: '2026-11-20T09:00', fim: '2026-11-20T09:30' };
const ANTES = '2026-11-12T10:00';   // "agora" antes do horário
const DEPOIS = '2026-11-21T08:00';  // "agora" depois do horário
const consulta = (status, extra = {}) => ({ id: status, pacienteNome: 'Paulo', inicio: HORARIO.inicio, fim: HORARIO.fim, status, ...extra });
const bloqueio = { inicio: '2026-11-20T00:00', fim: '2026-11-21T00:00', motivo: 'Congresso' };

describe('regras de classificação dos horários (#23)', () => {
  it('horário livre, sem consulta nem bloqueio', () => {
    expect(classificarHorario(HORARIO, [], [], ANTES)).toEqual({ estado: 'livre' });
  });

  it('consulta ativa ocupa o horário', () => {
    for (const status of ['AGENDADA', 'CONFIRMADA']) {
      expect(classificarHorario(HORARIO, [consulta(status)], [], ANTES)).toMatchObject({ estado: 'ocupado', consulta: { status } });
    }
  });

  it('consulta ativa dentro de bloqueio pede remarcação', () => {
    expect(classificarHorario(HORARIO, [consulta('CONFIRMADA')], [bloqueio], ANTES))
      .toMatchObject({ estado: 'conflito', consulta: { status: 'CONFIRMADA' }, bloqueio: { motivo: 'Congresso' } });
  });

  it('bloqueio sem consulta deixa o horário bloqueado', () => {
    expect(classificarHorario(HORARIO, [], [bloqueio], ANTES)).toMatchObject({ estado: 'bloqueado', bloqueio: { motivo: 'Congresso' } });
  });

  it('consulta cancelada devolve a vaga, mas fica registrada', () => {
    expect(classificarHorario(HORARIO, [consulta('CANCELADA')], [], ANTES)).toMatchObject({ estado: 'livre', consulta: { status: 'CANCELADA' } });
  });

  it('nova consulta no horário de uma cancelada volta a ocupar', () => {
    const resultado = classificarHorario(HORARIO, [consulta('CANCELADA'), consulta('AGENDADA', { id: 'nova' })], [], ANTES);
    expect(resultado).toMatchObject({ estado: 'ocupado', consulta: { id: 'nova' } });
  });

  it('horário que já passou fica indisponível, com o desfecho da consulta', () => {
    expect(classificarHorario(HORARIO, [], [], DEPOIS)).toMatchObject({ estado: 'passado' });
    expect(classificarHorario(HORARIO, [consulta('CONCLUIDA')], [], DEPOIS)).toMatchObject({ estado: 'passado', consulta: { status: 'CONCLUIDA' } });
    expect(classificarHorario(HORARIO, [consulta('CANCELADA'), consulta('FALTA', { id: 'f' })], [], DEPOIS))
      .toMatchObject({ estado: 'passado', consulta: { status: 'FALTA' } });
  });

  it('horário em andamento já não pode ser agendado', () => {
    expect(classificarHorario(HORARIO, [], [], '2026-11-20T09:10')).toMatchObject({ estado: 'passado' });
  });
});

describe('tela do calendário', () => {
  it('pede para escolher um médico', async () => {
    mockApi();
    renderAt('/calendario');
    expect(await screen.findByText('Selecione um médico para ver o calendário.')).toBeInTheDocument();
  });

  it('busca agenda, bloqueios e consultas da semana e mostra os horários por dia', async () => {
    const fetchMock = mockApi();
    renderAt('/calendario?medicoId=m1&data=2026-11-12');

    const tabela = await screen.findByRole('table');
    expect(within(tabela).getAllByRole('columnheader').map((th) => th.textContent))
      .toEqual(['Hora', 'seg 09/11', 'ter 10/11', 'qua 11/11', 'qui 12/11', 'sex 13/11', 'sáb 14/11', 'dom 15/11']);
    expect(within(tabela).getAllByRole('rowheader').map((th) => th.textContent)).toEqual(['08:00', '08:30']);
    const [consultas] = chamadasPara(fetchMock, '/consultas');
    expect(consultas.searchParams.get('medicoId')).toBe('m1');
    expect(consultas.searchParams.get('de')).toBe('2026-11-09');
    expect(consultas.searchParams.get('ate')).toBe('2026-11-15');
    expect(screen.getByText('09/11 a 15/11/2026')).toBeInTheDocument();
  });

  it('avança e volta uma semana', async () => {
    const fetchMock = mockApi();
    const user = userEvent.setup();
    const router = renderAt('/calendario?medicoId=m1&data=2026-11-12');
    await screen.findByRole('table');

    await user.click(screen.getByRole('button', { name: 'Próximo ›' }));
    await waitFor(() => expect(router.state.location.search).toContain('data=2026-11-19'));
    await waitFor(() => expect(chamadasPara(fetchMock, '/consultas').at(-1).searchParams.get('de')).toBe('2026-11-16'));

    await user.click(screen.getByRole('button', { name: '‹ Anterior' }));
    await waitFor(() => expect(router.state.location.search).toContain('data=2026-11-12'));
  });

  it('abre a visão do dia ao clicar no dia da semana', async () => {
    const fetchMock = mockApi();
    const user = userEvent.setup();
    renderAt('/calendario?medicoId=m1&data=2026-11-12');
    await screen.findByRole('table');

    await user.click(screen.getByRole('button', { name: 'Ver o dia Segunda-feira, 09/11/2026' }));

    expect(await screen.findByText('Segunda-feira, 09/11/2026')).toBeInTheDocument();
    expect([...document.querySelectorAll('.cal-lista__hora')].map((hora) => hora.textContent))
      .toEqual(['08:00 – 08:30', '08:30 – 09:00']);
    await waitFor(() => {
      const ultima = chamadasPara(fetchMock, '/consultas').at(-1);
      expect([ultima.searchParams.get('de'), ultima.searchParams.get('ate')]).toEqual(['2026-11-09', '2026-11-09']);
    });
  });

  it('lista as consultas canceladas do dia à parte', async () => {
    mockApi({ consultas: [{ id: 'c1', medicoId: 'm1', pacienteId: 'p1', pacienteNome: 'Carla Souza', especialidadeId: 'e1',
      especialidadeNome: 'Clínica Geral', inicio: '2026-11-09T08:30', fim: '2026-11-09T09:00', status: 'CANCELADA' }] });
    renderAt('/calendario?medicoId=m1&data=2026-11-09&visao=dia');

    expect(await screen.findByRole('heading', { name: 'Canceladas neste dia' })).toBeInTheDocument();
    expect(screen.getByText('08:30 · Carla Souza · Clínica Geral')).toBeInTheDocument();
  });

  it('mostra paciente, remarcação, bloqueio e cancelada na semana', async () => {
    const c = (id, inicio, fim, status, pacienteNome) => ({ id, medicoId: 'm1', pacienteId: id, pacienteNome,
      especialidadeId: 'e1', especialidadeNome: 'Clínica Geral', inicio, fim, status });
    mockApi({
      consultas: [
        c('c1', '2099-11-09T08:00', '2099-11-09T08:30', 'CONFIRMADA', 'Paulo Paciente'),
        c('c2', '2099-11-09T08:30', '2099-11-09T09:00', 'CANCELADA', 'Carla Souza'),
        c('c3', '2099-11-11T08:00', '2099-11-11T08:30', 'AGENDADA', 'Diego Santos'),
      ],
      bloqueios: [{ id: 'b1', medicoId: 'm1', inicio: '2099-11-11T00:00', fim: '2099-11-12T00:00', motivo: 'Congresso' }],
    });
    renderAt('/calendario?medicoId=m1&data=2099-11-09');

    const tabela = await screen.findByRole('table');
    expect(within(tabela).getByText('Paulo Paciente').closest('td')).toHaveClass('cal-celula--ocupado');
    expect(within(tabela).getByText('Livre (cancelada)').closest('td')).toHaveClass('cal-celula--livre');
    expect(within(tabela).getByText('Diego Santos · remarcar').closest('td')).toHaveClass('cal-celula--conflito');
    expect(screen.getByText('Remarcar (1)')).toBeInTheDocument();
  });

  it('horários que já passaram aparecem como indisponíveis', async () => {
    mockApi({ consultas: [{ id: 'c1', medicoId: 'm1', pacienteId: 'p1', pacienteNome: 'Paulo Paciente', especialidadeId: 'e1',
      especialidadeNome: 'Clínica Geral', inicio: '2020-11-09T08:00', fim: '2020-11-09T08:30', status: 'CONCLUIDA' }] });
    renderAt('/calendario?medicoId=m1&data=2020-11-09&visao=dia');

    expect(await screen.findByText('Indisponível (concluída)')).toBeInTheDocument();
    expect(screen.getByText('Paulo Paciente · Clínica Geral · Concluída')).toBeInTheDocument();
    expect(screen.getAllByText('Indisponível')).toHaveLength(1); // 08:30 vazio
  });

  it('avisa quando o médico não tem agenda configurada', async () => {
    mockApi({ agenda: null });
    renderAt('/calendario?medicoId=m1&data=2026-11-12');

    expect(await screen.findByText(/ainda não tem agenda configurada/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Configurar agenda' })).toHaveAttribute('href', '/agenda?medicoId=m1');
  });
});
