import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { routes } from '../routes';
import { entrarComo } from './sessaoTeste';
import { indicadoresDaUrl } from './fixtures/indicadores';
import { agendaPainel, consultaDia, consultaProxima, MEDICO_PAINEL } from './fixtures/consultasPainel';

const BUSCA = `?de=2026-11-20&ate=2026-11-23&dia=2026-11-23&medicoId=${MEDICO_PAINEL}`;
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

function mockApi() {
  let confirmada = false;
  const detalhe = () => consultaProxima({ id: 'consulta-proxima', status: confirmada ? 'CONFIRMADA' : 'AGENDADA', podeCancelar: true });
  const fetchMock = vi.fn(async (caminho, options) => {
    const url = new URL(caminho);
    if (url.pathname === '/consultas/consulta-proxima/status' && options.method === 'POST') {
      confirmada = true;
      return json(detalhe());
    }
    if (options.method !== 'GET') return json({ erro: 'Escrita inesperada.' }, 400);
    switch (url.pathname) {
      case '/health': return json({ status: 'ok' });
      case '/medicos': return json([{ id: MEDICO_PAINEL, nome: 'Ana Médica', ativo: true }]);
      case '/agendas': return json(agendaPainel());
      case '/consultas': return json([consultaDia()]);
      case '/consultas/lista': return json([detalhe()]);
      case '/indicadores': {
        const dados = indicadoresDaUrl(caminho);
        if (confirmada) {
          dados.confirmacao = { numerador: 5, denominador: 6, percentual: 83.33, situacao: 'DISPONIVEL' };
          Object.assign(dados.contagens, { agendadas: 0, confirmadas: 2, comConfirmacaoRegistrada: 5 });
        }
        return json(dados);
      }
      case '/consultas/consulta-proxima': return json(detalhe());
      case '/consultas/consulta-proxima/historico': return json({ consultaId: 'consulta-proxima', aposSequencia: 0,
        limite: 20, eventos: [], temMais: false, proximaSequencia: null, limiteAtingido: false, atualizacaoPendente: false });
      default: return json({}, 404);
    }
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function abrir() {
  const router = createMemoryRouter(routes, { initialEntries: [`/${BUSCA}`] });
  render(<RouterProvider router={router} />);
  return router;
}

beforeEach(() => { sessionStorage.clear(); entrarComo(); });

it('permite pular a navegação pelo teclado e entrar nas ações do painel sem alterar os filtros', async () => {
  mockApi();
  const user = userEvent.setup();
  const router = abrir();
  await screen.findByText('75,00%');
  await user.tab();
  expect(screen.getByRole('link', { name: 'Ir para o conteúdo principal' })).toHaveFocus();
  await user.keyboard('{Enter}');
  expect(screen.getByRole('main')).toHaveFocus();
  await user.tab();
  expect(within(screen.getByRole('main')).getByRole('link', { name: 'Nova consulta' })).toHaveFocus();
  await user.tab();
  expect(screen.getByLabelText('Médico do painel')).toHaveFocus();
  expect(router.state.location.search).toBe(BUSCA);
  expect(router.state.location.hash).toBe('');
});

it('organiza os indicadores abaixo do título da seção na hierarquia de cabeçalhos', async () => {
  mockApi();
  abrir();
  await screen.findByText('75,00%');
  const main = within(screen.getByRole('main'));
  expect(main.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  expect(main.getByRole('heading', { level: 2, name: 'Indicadores' })).toBeInTheDocument();
  for (const nome of ['Ocupação', 'Faltas', 'Cancelamentos', 'Confirmações', 'Vagas preenchidas pela fila'])
    expect(main.getByRole('heading', { level: 3, name: nome })).toBeInTheDocument();
});

it('oferece foco e instruções acessíveis para percorrer as duas tabelas', async () => {
  mockApi();
  const user = userEvent.setup();
  const router = abrir();
  await screen.findByText('Bruno da Lista');
  for (const nome of ['Consultas na agenda do dia', 'Resumo das próximas consultas']) {
    const regiao = screen.getByRole('region', { name: nome });
    expect(regiao).toHaveAttribute('tabindex', '0');
    expect(regiao).toHaveAccessibleDescription('Para ver todas as colunas, deslize a tabela para os lados ou coloque-a em foco e use as setas.');
    regiao.focus();
    await user.keyboard('{ArrowRight}');
    expect(regiao).toHaveFocus();
  }
  expect(router.state.location.search).toBe(BUSCA);
});

it('associa as mensagens de erro aos campos sem esconder as seções válidas', async () => {
  mockApi();
  abrir();
  await screen.findByText('75,00%');
  fireEvent.change(screen.getByLabelText('Fim dos indicadores'), { target: { value: '2026-11-19' } });
  const fim = screen.getByLabelText('Fim dos indicadores');
  expect(fim).toHaveAttribute('aria-invalid', 'true');
  expect(fim).toHaveAccessibleDescription('O fim dos indicadores deve ser igual ou posterior ao início.');
  expect(await screen.findByText('Paula da Agenda')).toBeInTheDocument();
  expect(screen.getByText('Bruno da Lista')).toBeInTheDocument();
});

it('anuncia o escopo selecionado em uma região de status', async () => {
  mockApi();
  abrir();
  const escopo = await screen.findByText('Escopo do painel: Ana Médica.');
  expect(escopo).toHaveAttribute('role', 'status');
  expect(escopo).toHaveAttribute('aria-atomic', 'true');
});

it.each(['retorno', 'inicio', 'marca'])('retorna por %s com dados renovados após confirmar sem repetir a escrita', async (destino) => {
  const fetchMock = mockApi();
  const user = userEvent.setup();
  const router = abrir();
  expect(await screen.findByText('66,67%')).toBeInTheDocument();
  await user.click(within(screen.getByRole('table', { name: 'Resumo das próximas consultas' })).getByRole('link', { name: /Ver detalhes/ }));
  await user.click(await screen.findByRole('button', { name: 'Confirmar consulta' }));
  expect(await screen.findByText('Consulta confirmada.')).toBeInTheDocument();
  const voltar = destino === 'retorno' ? screen.getByRole('link', { name: '‹ Voltar ao painel' })
    : destino === 'inicio' ? within(screen.getByRole('navigation', { name: 'Navegação principal' })).getByRole('link', { name: 'Início' })
      : screen.getByRole('link', { name: /ConsultaFácil.*Agendamento de consultas médicas/ });
  await user.click(voltar);
  expect(await screen.findByText('83,33%')).toBeInTheDocument();
  await waitFor(() => expect(within(screen.getByRole('table', { name: 'Resumo das próximas consultas' })).getByText('Confirmada')).toBeInTheDocument());
  const escritas = fetchMock.mock.calls.filter(([, options]) => options.method !== 'GET');
  expect(escritas).toHaveLength(1);
  expect(new URL(escritas[0][0]).pathname).toBe('/consultas/consulta-proxima/status');
  expect(JSON.parse(escritas[0][1].body)).toEqual({ status: 'CONFIRMADA' });
  for (const caminho of ['/indicadores', '/agendas', '/consultas', '/consultas/lista'])
    expect(fetchMock.mock.calls.filter(([url]) => new URL(url).pathname === caminho)).toHaveLength(2);
  expect(router.state.location.search).toBe(BUSCA);
  expect(screen.getByLabelText('Médico do painel')).toHaveValue(MEDICO_PAINEL);
  expect(screen.getByLabelText('Dia da agenda')).toHaveValue('2026-11-23');
});
