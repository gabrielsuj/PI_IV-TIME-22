import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { routes } from '../routes';
import { atalhosPainel, origemPainel, retornoPainel } from '../utils/painel';
import { entrarComo, SECRETARIA_TESTE } from './sessaoTeste';
import { indicadoresDaUrl } from './fixtures/indicadores';
import { agendaPainel, consultaDia, consultaProxima, MEDICO_PAINEL, OUTRO_MEDICO_PAINEL } from './fixtures/consultasPainel';

const BUSCA = `?de=2026-11-20&ate=2026-11-23&dia=2026-11-23&medicoId=${MEDICO_PAINEL}`;
const MEDICO = { id: 'login-do-medico', nome: 'Ana Médica', perfil: 'MEDICO' };
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

function mockApi({
  agenda = (medicoId) => ({ ...agendaPainel(medicoId), jornadas: [{ diaSemana: 1, faixas: [{ inicioMinuto: 480, fimMinuto: 780 }] }] }),
  consultas = (_url, medicoId) => [consultaDia({ medicoId })],
} = {}) {
  const fetchMock = vi.fn(async (caminho, options) => {
    if (options.method !== 'GET') throw new Error('A navegação não deve escrever dados.');
    const url = new URL(caminho);
    const cadastro = url.searchParams.get('medicoId');
    const medicoId = cadastro && cadastro !== 'meu' ? cadastro : MEDICO_PAINEL;
    switch (url.pathname) {
      case '/health': return json({ status: 'ok' });
      case '/medicos': return json([{ id: MEDICO_PAINEL, nome: 'Ana Médica', ativo: true, especialidadeIds: ['especialidade'], crmNumero: '123', crmUf: 'SP' }]);
      case '/especialidades': return json([{ id: 'especialidade', nome: 'Clínica Geral', ativa: true }]);
      case '/pacientes': return json([]);
      case '/agendas': return json(agenda(medicoId));
      case '/bloqueios': return json([]);
      case '/indicadores': return json(indicadoresDaUrl(caminho));
      case '/consultas': return json(consultas(url, medicoId));
      case '/consultas/lista': return json([consultaProxima({ medicoId, podeCancelar: true })]);
      default: {
        const historico = url.pathname.match(/^\/consultas\/([^/]+)\/historico$/);
        if (historico) return json({ consultaId: decodeURIComponent(historico[1]), aposSequencia: 0, limite: 20,
          eventos: [], temMais: false, proximaSequencia: null, limiteAtingido: false, atualizacaoPendente: false });
        const detalhe = url.pathname.match(/^\/consultas\/([^/]+)$/);
        if (detalhe) return json(consultaProxima({ id: decodeURIComponent(detalhe[1]), podeCancelar: true }));
        return json({}, 404);
      }
    }
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function abrir(path = `/${BUSCA}`) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(<RouterProvider router={router} />);
  return router;
}

function conferirFiltros() {
  expect(screen.getByLabelText('Médico do painel')).toHaveValue(MEDICO_PAINEL);
  expect(screen.getByLabelText('Início dos indicadores')).toHaveValue('2026-11-20');
  expect(screen.getByLabelText('Fim dos indicadores')).toHaveValue('2026-11-23');
  expect(screen.getByLabelText('Dia da agenda')).toHaveValue('2026-11-23');
}

beforeEach(() => { sessionStorage.clear(); entrarComo(); });

it.each([
  ['Consultas na agenda do dia', 'consulta-dia'],
  ['Resumo das próximas consultas', 'consulta-proxima'],
])('abre o detalhe existente pela tabela %s e volta com os filtros preservados', async (tabela, id) => {
  const fetchMock = mockApi();
  const user = userEvent.setup();
  const router = abrir();
  await screen.findByText(tabela === 'Consultas na agenda do dia' ? 'Paula da Agenda' : 'Bruno da Lista');
  expect(within(screen.getByRole('main')).queryByRole('button', { name: 'Cancelar consulta' })).not.toBeInTheDocument();
  await user.click(within(screen.getByRole('table', { name: tabela })).getByRole('link', { name: /Ver detalhes da consulta/ }));
  expect(router.state.location.pathname).toBe(`/consultas/${id}`);
  expect(await screen.findByRole('heading', { level: 1, name: 'Detalhes da consulta' })).toBeInTheDocument();
  await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => new URL(url).pathname === `/consultas/${id}/historico`)).toBe(true));
  await user.click(screen.getByRole('link', { name: '‹ Voltar ao painel' }));
  expect(await screen.findByText('Paula da Agenda')).toBeInTheDocument();
  conferirFiltros();
  expect(fetchMock.mock.calls.every(([, options]) => options.method === 'GET')).toBe(true);
});

it('atalho do calendário leva médico e dia e preserva o retorno após trocar a visualização', async () => {
  mockApi();
  const user = userEvent.setup();
  const router = abrir();
  await screen.findByText('Paula da Agenda');
  await user.click(screen.getByRole('link', { name: 'Abrir calendário' }));
  expect(router.state.location.pathname).toBe('/calendario');
  expect(Object.fromEntries(new URLSearchParams(router.state.location.search))).toEqual({
    medicoId: MEDICO_PAINEL, visao: 'dia', data: '2026-11-23', diaClinica: '1',
  });
  expect(await screen.findByText('Horários no fuso da agenda: America/Sao_Paulo.')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Próximo ›' }));
  await waitFor(() => expect(router.state.location.search).toContain('data=2026-11-24'));
  await user.click(screen.getByRole('link', { name: '‹ Voltar ao painel' }));
  expect(await screen.findByText('Paula da Agenda')).toBeInTheDocument();
  conferirFiltros();
});

it('leva as consultas do dia da clínica ao calendário quando a agenda está em UTC', async () => {
  const fetchMock = mockApi({
    agenda: (medicoId) => ({ ...agendaPainel(medicoId, 'UTC'), jornadas: [
      { diaSemana: 1, faixas: [{ inicioMinuto: 780, fimMinuto: 810 }] },
      { diaSemana: 2, faixas: [{ inicioMinuto: 30, fimMinuto: 60 }] },
    ] }),
    consultas: (url, medicoId) => {
      const clinica = url.searchParams.get('fusoHorario') === 'America/Sao_Paulo';
      const registros = [
        consultaDia({ medicoId, inicio: clinica ? '2026-11-23T10:00' : '2026-11-23T13:00',
          fim: clinica ? '2026-11-23T10:30' : '2026-11-23T13:30' }),
        consultaDia({ id: 'dia-seguinte-utc', medicoId, pacienteNome: 'Bruna às 21h30',
          inicio: clinica ? '2026-11-23T21:30' : '2026-11-24T00:30', fim: clinica ? '2026-11-23T22:00' : '2026-11-24T01:00' }),
      ];
      return registros.filter(({ inicio }) => inicio.slice(0, 10) >= url.searchParams.get('de')
        && inicio.slice(0, 10) <= url.searchParams.get('ate'));
    },
  });
  const user = userEvent.setup();
  const router = abrir();
  expect(await screen.findByText('Bruna às 21h30')).toBeInTheDocument();
  await user.click(screen.getByRole('link', { name: 'Abrir calendário' }));
  expect(await screen.findByText('Horários no fuso da agenda: UTC.')).toBeInTheDocument();
  expect(screen.getByText('O dia 23/11/2026 no fuso da clínica abrange os dias abaixo no fuso da agenda.')).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: 'Segunda-feira, 23/11/2026' })).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: 'Terça-feira, 24/11/2026' })).toBeInTheDocument();
  expect(screen.getByText(/Paula da Agenda ·/)).toBeInTheDocument();
  expect(screen.getByText(/Bruna às 21h30 ·/)).toBeInTheDocument();
  const requisicoes = fetchMock.mock.calls.map(([url]) => new URL(url));
  for (const rota of ['/consultas', '/bloqueios']) {
    const consultaLocal = requisicoes.findLast((url) => url.pathname === rota);
    expect(Object.fromEntries(consultaLocal.searchParams)).toEqual({
      medicoId: MEDICO_PAINEL, de: '2026-11-23', ate: '2026-11-24',
    });
  }

  await user.click(screen.getByRole('button', { name: 'Semana' }));
  expect(router.state.location.search).not.toContain('diaClinica');
  await user.click(await screen.findByRole('button', { name: 'Ver o dia Segunda-feira, 23/11/2026' }));
  expect(await screen.findByText(/Paula da Agenda ·/)).toBeInTheDocument();
  expect(screen.queryByText(/Bruna às 21h30 ·/)).not.toBeInTheDocument();
  await user.click(screen.getByRole('link', { name: '‹ Voltar ao painel' }));
  expect(await screen.findByText('Bruna às 21h30')).toBeInTheDocument();
  conferirFiltros();
});

it('lista recebe o médico e mantém o retorno ao painel ao filtrar e abrir detalhes', async () => {
  mockApi();
  const user = userEvent.setup();
  const router = abrir();
  await screen.findByText('Bruno da Lista');
  await user.click(screen.getByRole('link', { name: 'Abrir lista de consultas' }));
  expect(router.state.location.pathname).toBe('/consultas');
  expect(new URLSearchParams(router.state.location.search).get('medicoId')).toBe(MEDICO_PAINEL);
  await user.selectOptions(screen.getByLabelText('Status'), 'CONFIRMADA');
  await user.click(await screen.findByRole('link', { name: /Ver detalhes da consulta/ }));
  expect(await screen.findByRole('link', { name: '‹ Voltar ao painel' })).toBeInTheDocument();
  await user.click(screen.getByRole('link', { name: '‹ Voltar ao painel' }));
  expect(await screen.findByText('Paula da Agenda')).toBeInTheDocument();
  conferirFiltros();
});

it('secretaria entra no agendamento existente e retorna sem perder os filtros ou iniciar uma reserva', async () => {
  const fetchMock = mockApi();
  const user = userEvent.setup();
  const router = abrir();
  await screen.findByText('Paula da Agenda');
  await user.click(within(screen.getByRole('main')).getByRole('link', { name: 'Nova consulta' }));
  expect(router.state.location.pathname).toBe('/nova-consulta');
  expect(await screen.findByRole('heading', { name: 'Nova consulta' })).toBeInTheDocument();
  await user.click(screen.getByRole('link', { name: 'Voltar ao painel' }));
  expect(await screen.findByText('Paula da Agenda')).toBeInTheDocument();
  conferirFiltros();
  expect(fetchMock.mock.calls.every(([url, options]) => options.method === 'GET' && new URL(url).pathname !== '/vagas')).toBe(true);
});

it('médico mantém detalhes e calendário próprios sem acesso à criação de reserva', async () => {
  entrarComo(MEDICO);
  const fetchMock = mockApi();
  const user = userEvent.setup();
  const router = abrir(`/${BUSCA.replace(MEDICO_PAINEL, OUTRO_MEDICO_PAINEL)}`);
  await screen.findByText('Paula da Agenda');
  const main = within(screen.getByRole('main'));
  expect(main.queryByRole('link', { name: 'Nova consulta' })).not.toBeInTheDocument();
  const calendario = new URL(main.getByRole('link', { name: 'Abrir calendário' }).getAttribute('href'), 'http://app.teste');
  expect(calendario.searchParams.has('medicoId')).toBe(false);
  await user.click(within(screen.getByRole('table', { name: 'Consultas na agenda do dia' })).getByRole('link', { name: /Ver detalhes da consulta/ }));
  expect(new URLSearchParams(router.state.location.state.origemPainel.busca).has('medicoId')).toBe(false);
  await user.click(await screen.findByRole('link', { name: '‹ Voltar ao painel' }));
  const antes = fetchMock.mock.calls.length;
  await act(async () => { await router.navigate('/nova-consulta'); });
  expect(await screen.findByRole('heading', { name: 'Acesso não permitido' })).toBeInTheDocument();
  expect(fetchMock.mock.calls.slice(antes).every(([url]) => !['/pacientes', '/especialidades'].includes(new URL(url).pathname))).toBe(true);
});

it('paciente preserva o atalho de agendamento e a navegação pessoal existente', async () => {
  entrarComo({ id: 'paciente', nome: 'Paciente', perfil: 'PACIENTE' });
  mockApi();
  const user = userEvent.setup();
  const router = abrir();
  await user.click(within(screen.getByRole('main')).getByRole('link', { name: 'Nova consulta' }));
  expect(router.state.location.state).toBeNull();
  expect(await screen.findByRole('link', { name: 'Voltar ao início' })).toHaveAttribute('href', '/');
  expect(screen.queryByRole('link', { name: 'Voltar ao painel' })).not.toBeInTheDocument();
});

it('acesso direto ao detalhe preserva o retorno normal para consultas', async () => {
  mockApi();
  abrir('/consultas/consulta-proxima');
  expect(await screen.findByRole('link', { name: '‹ Voltar para consultas' })).toHaveAttribute('href', '/consultas');
  expect(screen.queryByRole('link', { name: '‹ Voltar ao painel' })).not.toBeInTheDocument();
});

it('guarda no retorno apenas os filtros conhecidos e o vínculo com a conta', () => {
  const origem = origemPainel({ pathname: '/', search: `${BUSCA}&token=nao-salvar&destino=https://outro.local` }, SECRETARIA_TESTE);
  expect(origem.origemPainel.usuarioId).toBe(SECRETARIA_TESTE.id);
  expect(origem.origemPainel.busca).not.toMatch(/token|nao-salvar|destino|https/);
  expect(retornoPainel({ pathname: '/consultas/c1', state: origem }, SECRETARIA_TESTE)).toBe(`/${BUSCA}`);
  expect(retornoPainel({ pathname: '/consultas/c1', state: origem }, { ...SECRETARIA_TESTE, id: 'outra-conta' })).toBeNull();
});

it('estado adulterado não cria um redirecionamento externo', () => {
  const state = { origemPainel: { usuarioId: SECRETARIA_TESTE.id, busca: 'https://outro.local?token=segredo' } };
  expect(retornoPainel({ pathname: '/consultas/c1', state }, SECRETARIA_TESTE)).toBe('/');
});

it('filtros inválidos desabilitam atalhos sem descartar silenciosamente o médico escolhido', async () => {
  mockApi();
  abrir('/?medicoId=invalido&dia=2026-02-30');
  expect(await screen.findByRole('button', { name: 'Abrir calendário' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Ver consultas' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Ver consultas' })).not.toHaveAttribute('state');
  expect(atalhosPainel('?medicoId=invalido', 'SECRETARIA_ADMIN', '2026-11-23').consultas).toBeNull();
});
