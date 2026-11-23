/*
Autoria: Murilo Moraes
RA: 25000073
*/
/**
 * Textos e rotas dos avisos de vaga (#58).
 */
import { describe, expect, it } from 'vitest';
import { buscaDeOutraVaga, explicarExpiracao } from '../utils/avisoVaga';

const MEDICO = 'a'.repeat(24);
const ESPECIALIDADE = 'b'.repeat(24);

describe('explicarExpiracao', () => {
  it('explica a vaga ocupada e oferece outra busca', () => {
    const explicacao = explicarExpiracao('VAGA_OCUPADA');
    expect(explicacao.rotulo).toBe('Vaga ocupada');
    expect(explicacao.texto).toMatch(/Outro paciente confirmou/);
    expect(explicacao.texto).toMatch(/continua na fila/);
    expect(explicacao.buscarOutra).toBe(true);
  });

  it('manda o paciente para a fila quando o prazo terminou', () => {
    const explicacao = explicarExpiracao('PRAZO_ENCERRADO');
    expect(explicacao.rotulo).toBe('Prazo encerrado');
    expect(explicacao.texto).toMatch(/fila de espera/);
    expect(explicacao.buscarOutra).toBe(false);
  });

  it('explica a oferta desatualizada pela mudança na agenda', () => {
    const explicacao = explicarExpiracao('OPORTUNIDADE_DESATUALIZADA');
    expect(explicacao.rotulo).toBe('Oferta desatualizada');
    expect(explicacao.texto).toMatch(/agenda do médico mudou/);
    expect(explicacao.buscarOutra).toBe(true);
  });

  it.each([undefined, null, 'MOTIVO_NOVO'])('usa uma mensagem genérica para o motivo %s', (motivo) => {
    expect(explicarExpiracao(motivo)).toEqual({
      rotulo: 'Indisponível',
      texto: 'A vaga deste aviso não está mais disponível.',
      buscarOutra: true,
    });
  });

  it('sempre devolve rótulo e texto preenchidos', () => {
    for (const motivo of ['VAGA_OCUPADA', 'PRAZO_ENCERRADO', 'OPORTUNIDADE_DESATUALIZADA', undefined]) {
      const { rotulo, texto } = explicarExpiracao(motivo);
      expect(rotulo.length).toBeGreaterThan(0);
      expect(texto.endsWith('.')).toBe(true);
    }
  });
});

describe('buscaDeOutraVaga', () => {
  it('leva o médico e a especialidade do aviso para a busca', () => {
    expect(buscaDeOutraVaga({ medicoId: MEDICO, especialidadeId: ESPECIALIDADE })).toEqual({
      to: '/nova-consulta',
      state: { buscaVaga: { especialidadeId: ESPECIALIDADE, medicoId: MEDICO } },
    });
  });

  it('ignora o médico sem a especialidade, porque a busca começa pela especialidade', () => {
    expect(buscaDeOutraVaga({ medicoId: MEDICO })).toEqual({ to: '/nova-consulta', state: undefined });
  });

  it('mantém só a especialidade quando o aviso não traz médico', () => {
    expect(buscaDeOutraVaga({ especialidadeId: ESPECIALIDADE }).state).toEqual({
      buscaVaga: { especialidadeId: ESPECIALIDADE },
    });
  });

  it('abre a busca vazia para um aviso antigo ou ausente', () => {
    expect(buscaDeOutraVaga(undefined)).toEqual({ to: '/nova-consulta', state: undefined });
    expect(buscaDeOutraVaga({})).toEqual({ to: '/nova-consulta', state: undefined });
  });
});
