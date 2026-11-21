/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { beforeEach, expect, it, vi } from 'vitest';
import { buscarMedicosPainel } from '../services/medicosPainelService';
import { entrarComo } from './sessaoTeste';
import { MEDICO_PAINEL } from './fixtures/consultasPainel';

const json = (dados, status = 200) => new Response(JSON.stringify(dados), { status, headers: { 'Content-Type': 'application/json' } });
const medico = { id: MEDICO_PAINEL, nome: 'Ana Médica', ativo: true };
beforeEach(() => { sessionStorage.clear(); entrarComo(); });

// IDs e nomes inválidos são tratados como falha de contrato, mantendo a distinção
// entre catálogo vazio e resposta incorreta.
it('lê os médicos com a sessão e retorna somente os campos usados pelo painel', async () => {
  const fetchMock = vi.fn(async () => json([{ ...medico, crmNumero: '123', crmUf: 'SP', usuarioId: 'outro-id' }]));
  vi.stubGlobal('fetch', fetchMock);
  expect(await buscarMedicosPainel({})).toEqual([medico]);
  expect(fetchMock).toHaveBeenCalledExactlyOnceWith('http://api.teste/medicos', expect.objectContaining({
    method: 'GET', headers: expect.objectContaining({ Authorization: 'Bearer token-de-teste' }),
  }));
});

it('lista vazia real é preservada', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => json([])));
  expect(await buscarMedicosPainel({})).toEqual([]);
});

it.each([
  null, {}, [null], [{ ...medico, id: {} }], [{ ...medico, id: 'invalido' }],
  [{ ...medico, nome: {} }], [{ ...medico, nome: ' ' }], [{ ...medico, ativo: 'true' }], [medico, medico],
].map((dados) => [dados]))('recusa catálogo inválido %j sem tratar como lista vazia', async (dados) => {
  vi.stubGlobal('fetch', vi.fn(async () => json(dados)));
  await expect(buscarMedicosPainel({})).rejects.toThrow('Não foi possível ler a lista de médicos recebida.');
});

it('preserva médicos inativos e normaliza o ID textual legado', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => json([{ _id: 'ABCDEFABCDEFABCDEFABCDEF', nome: 'Médico Inativo', ativo: false }])));
  expect(await buscarMedicosPainel({})).toEqual([{ id: 'abcdefabcdefabcdefabcdef', nome: 'Médico Inativo', ativo: false }]);
});

it('propaga falha de leitura sem fabricar registros', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => json({ erro: 'Catálogo indisponível.' }, 503)));
  await expect(buscarMedicosPainel({})).rejects.toMatchObject({ status: 503, message: 'Catálogo indisponível.' });
});
