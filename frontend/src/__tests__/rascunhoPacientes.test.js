import { describe, expect, it } from 'vitest';
import { encerrarRascunhos, guardarRascunho, iniciarRascunhos, recuperarRascunho, removerRascunho, suspenderRascunhos } from '../services/rascunhoPacientes';

const values = { nome: 'Teste', cpf: '01234567890', email: 'teste@example.com' };

describe('isolamento dos rascunhos de pacientes', () => {
  it('exige reautenticação, isola a rota e suporta respostas 401 simultâneas', () => {
    iniciarRascunhos('u1');
    guardarRascunho('u1', '/pacientes?origem=a', null, values);
    expect(recuperarRascunho('u1', '/pacientes?origem=a')).toBeNull();
    suspenderRascunhos();
    suspenderRascunhos();
    expect(recuperarRascunho('u1', '/pacientes?origem=a')).toBeNull();
    iniciarRascunhos('u1');
    expect(recuperarRascunho('u1', '/pacientes?origem=b')).toBeNull();
    expect(recuperarRascunho('u1', '/pacientes?origem=a')?.initialValues).toEqual(values);
  });

  it('descarta os dados de uma conta anterior mesmo se ela voltar depois', () => {
    iniciarRascunhos('u1');
    guardarRascunho('u1', '/pacientes', null, values);
    suspenderRascunhos();
    iniciarRascunhos('u2');
    expect(recuperarRascunho('u2', '/pacientes')).toBeNull();
    iniciarRascunhos('u1');
    expect(recuperarRascunho('u1', '/pacientes')).toBeNull();
  });

  it('não aceita gravação tardia após logout e limpa no cancelamento/conclusão', () => {
    iniciarRascunhos('u1');
    guardarRascunho('u1', '/pacientes', null, values);
    encerrarRascunhos();
    guardarRascunho('u1', '/pacientes', null, values);
    iniciarRascunhos('u1');
    suspenderRascunhos();
    iniciarRascunhos('u1');
    expect(recuperarRascunho('u1', '/pacientes')).toBeNull();
    guardarRascunho('u1', '/pacientes', null, values);
    removerRascunho('u1', '/pacientes');
    suspenderRascunhos();
    iniciarRascunhos('u1');
    expect(recuperarRascunho('u1', '/pacientes')).toBeNull();
  });

  it('guarda somente os campos permitidos e devolve cópias', () => {
    iniciarRascunhos('u1');
    guardarRascunho('u1', '/pacientes', { id: 'p1', senha: 'não guardar' }, { ...values, senha: 'não guardar', token: 'não guardar' });
    suspenderRascunhos();
    iniciarRascunhos('u1');
    const restored = recuperarRascunho('u1', '/pacientes');
    expect(restored.initialValues).toEqual(values);
    expect(restored.record).toEqual({ id: 'p1', ...values });
    restored.initialValues.nome = 'Alteração externa';
    expect(recuperarRascunho('u1', '/pacientes').initialValues.nome).toBe(values.nome);
  });
});
