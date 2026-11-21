import { act, renderHook, waitFor } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { AuthContext } from '../contexts/authContext';
import { useLeituraPainel } from '../hooks/useLeituraPainel';
import { SECRETARIA_TESTE } from './sessaoTeste';

function pendente() {
  let resolver, rejeitar;
  const promessa = new Promise((resolve, reject) => { resolver = resolve; rejeitar = reject; });
  return { promessa, resolver, rejeitar };
}
const wrapper = ({ children }) => <AuthContext.Provider value={{ usuario: SECRETARIA_TESTE }}>{children}</AuthContext.Provider>;

it('ignora respostas e erros atrasados de filtros anteriores', async () => {
  const antigo = pendente();
  const ler = vi.fn().mockImplementationOnce(() => antigo.promessa).mockResolvedValueOnce('novo');
  const view = renderHook(({ medicoId }) => useLeituraPainel(ler, { medicoId }), { wrapper, initialProps: { medicoId: 'primeiro' } });
  view.rerender({ medicoId: 'segundo' });
  await waitFor(() => expect(view.result.current.dados).toBe('novo'));
  await act(async () => antigo.rejeitar(new Error('Erro da seleção antiga')));
  expect(view.result.current.dados).toBe('novo');
  expect(view.result.current.erro).toBeNull();
  expect(ler.mock.calls[0][1].signal.aborted).toBe(true);
});

it('trocar a conta esconde os dados anteriores e invalida a resposta em trânsito', async () => {
  const antigo = pendente();
  let usuario = SECRETARIA_TESTE;
  const contexto = ({ children }) => <AuthContext.Provider value={{ usuario }}>{children}</AuthContext.Provider>;
  const ler = vi.fn().mockResolvedValueOnce('primeira-conta').mockImplementationOnce(() => antigo.promessa).mockResolvedValueOnce('segunda-conta');
  const view = renderHook(() => useLeituraPainel(ler, {}), { wrapper: contexto });
  await waitFor(() => expect(view.result.current.dados).toBe('primeira-conta'));
  act(() => view.result.current.atualizar());
  usuario = { ...SECRETARIA_TESTE, id: 'outra-conta' };
  view.rerender();
  expect(view.result.current.dados).toBeNull();
  await waitFor(() => expect(view.result.current.dados).toBe('segunda-conta'));
  await act(async () => antigo.resolver('resultado-da-primeira-conta'));
  expect(view.result.current.dados).toBe('segunda-conta');
});

it('não repete uma leitura em andamento mesmo se um callback anterior for acionado de novo', async () => {
  const nova = pendente();
  const ler = vi.fn().mockResolvedValueOnce('inicial').mockImplementationOnce(() => nova.promessa);
  const { result } = renderHook(() => useLeituraPainel(ler, {}), { wrapper });
  await waitFor(() => expect(result.current.dados).toBe('inicial'));
  const atualizar = result.current.atualizar;
  act(() => atualizar());
  expect(result.current.carregando).toBe(true);
  expect(result.current.dados).toBeNull();
  act(() => atualizar());
  expect(ler).toHaveBeenCalledTimes(2);
  await act(async () => nova.resolver('atualizado'));
  expect(result.current.dados).toBe('atualizado');
});

it('erro na atualização remove o resultado antigo e permite outra tentativa', async () => {
  const ler = vi.fn().mockResolvedValueOnce('inicial').mockRejectedValueOnce(new Error('Falha atual')).mockResolvedValueOnce('recuperado');
  const { result } = renderHook(() => useLeituraPainel(ler, {}), { wrapper });
  await waitFor(() => expect(result.current.dados).toBe('inicial'));
  act(() => result.current.atualizar());
  await waitFor(() => expect(result.current.erro?.message).toBe('Falha atual'));
  expect(result.current.dados).toBeNull();
  act(() => result.current.atualizar());
  await waitFor(() => expect(result.current.dados).toBe('recuperado'));
  expect(ler).toHaveBeenCalledTimes(3);
});

it('não lê quando desabilitado e aborta quando o chamador sai', async () => {
  const ler = vi.fn(() => new Promise(() => {}));
  const view = renderHook(({ habilitado }) => useLeituraPainel(ler, {}, habilitado), { wrapper, initialProps: { habilitado: false } });
  expect(ler).not.toHaveBeenCalled();
  expect(view.result.current.carregando).toBe(false);
  view.rerender({ habilitado: true });
  expect(ler).toHaveBeenCalledTimes(1);
  view.unmount();
  expect(ler.mock.calls[0][1].signal.aborted).toBe(true);
});

it('suporta o ciclo de montagem do StrictMode sem uma resposta abortada sobrescrever a atual', async () => {
  const primeira = pendente();
  const ler = vi.fn().mockImplementationOnce(() => primeira.promessa).mockResolvedValueOnce('atual');
  const { result } = renderHook(() => useLeituraPainel(ler, {}), { wrapper, reactStrictMode: true });
  await waitFor(() => expect(result.current.dados).toBe('atual'));
  expect(ler).toHaveBeenCalledTimes(2);
  await act(async () => primeira.resolver('abortada'));
  expect(result.current.dados).toBe('atual');
  expect(ler.mock.calls[0][1].signal.aborted).toBe(true);
});
