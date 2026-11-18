import { describe, expect, it } from 'vitest';
import {
  encerrarRascunhoAgendamento, guardarRascunhoAgendamento, iniciarRascunhoAgendamento,
  recuperarRascunhoAgendamento, removerRascunhoAgendamento, suspenderRascunhoAgendamento,
} from '../services/rascunhoAgendamento';

const usuario = { id: 'u1', perfil: 'SECRETARIA_ADMIN' };
const dados = {
  filtros: { especialidadeId: 'e1', medicoId: 'm1', data: '2026-12-01' },
  paciente: { id: 'p1', nome: 'Maria Teste', cpf: '01234567890' },
};

describe('rascunho do agendamento em memória', () => {
  it('recupera somente após reautenticar a mesma conta e preserva no desmonte por 401', () => {
    iniciarRascunhoAgendamento(usuario);
    guardarRascunhoAgendamento(usuario, dados);
    expect(recuperarRascunhoAgendamento(usuario)).toBeNull();
    suspenderRascunhoAgendamento();
    suspenderRascunhoAgendamento();
    removerRascunhoAgendamento(usuario);
    guardarRascunhoAgendamento(usuario, { filtros: {}, paciente: null });
    expect(recuperarRascunhoAgendamento(usuario)).toBeNull();
    iniciarRascunhoAgendamento(usuario);
    expect(recuperarRascunhoAgendamento(usuario)).toEqual(dados);
  });

  it.each([
    { id: 'u2', perfil: 'SECRETARIA_ADMIN' },
    { id: 'u1', perfil: 'PACIENTE' },
    { id: 'u1', perfil: 'MEDICO' },
  ])('descarta o contexto ao trocar conta ou perfil (%j)', (outraConta) => {
    iniciarRascunhoAgendamento(usuario);
    guardarRascunhoAgendamento(usuario, dados);
    suspenderRascunhoAgendamento();
    iniciarRascunhoAgendamento(outraConta);
    expect(recuperarRascunhoAgendamento(outraConta)).toBeNull();
    iniciarRascunhoAgendamento(usuario);
    expect(recuperarRascunhoAgendamento(usuario)).toBeNull();
  });

  it('descarta ao sair e recusa gravação tardia da sessão encerrada', () => {
    iniciarRascunhoAgendamento(usuario);
    guardarRascunhoAgendamento(usuario, dados);
    encerrarRascunhoAgendamento();
    guardarRascunhoAgendamento(usuario, dados);
    iniciarRascunhoAgendamento(usuario);
    suspenderRascunhoAgendamento();
    iniciarRascunhoAgendamento(usuario);
    expect(recuperarRascunhoAgendamento(usuario)).toBeNull();
  });

  it('limpa na saída da página, conclusão ou início de novo agendamento', () => {
    iniciarRascunhoAgendamento(usuario);
    guardarRascunhoAgendamento(usuario, dados);
    removerRascunhoAgendamento(usuario);
    suspenderRascunhoAgendamento();
    iniciarRascunhoAgendamento(usuario);
    expect(recuperarRascunhoAgendamento(usuario)).toBeNull();
    guardarRascunhoAgendamento(usuario, dados);
    guardarRascunhoAgendamento(usuario, { filtros: {}, paciente: null });
    suspenderRascunhoAgendamento();
    iniciarRascunhoAgendamento(usuario);
    expect(recuperarRascunhoAgendamento(usuario)).toBeNull();
  });

  it('não guarda credenciais, vagas ou revisão e devolve cópias dos campos permitidos', () => {
    iniciarRascunhoAgendamento(usuario);
    guardarRascunhoAgendamento(usuario, {
      ...dados, senha: 'segredo', token: 'segredo', horario: { inicio: 'não restaurar' }, revisando: true,
      filtros: { ...dados.filtros, token: 'segredo' }, paciente: { ...dados.paciente, senha: 'segredo' },
    });
    suspenderRascunhoAgendamento();
    iniciarRascunhoAgendamento(usuario);
    const recuperado = recuperarRascunhoAgendamento(usuario);
    expect(recuperado).toEqual(dados);
    recuperado.paciente.nome = 'Alteração externa';
    recuperado.filtros.medicoId = 'm2';
    expect(recuperarRascunhoAgendamento(usuario)).toEqual(dados);
  });

  it('paciente recupera apenas filtros, sem receber seleção de outro paciente', () => {
    const paciente = { id: 'u-paciente', perfil: 'PACIENTE' };
    iniciarRascunhoAgendamento(paciente);
    guardarRascunhoAgendamento(paciente, dados);
    suspenderRascunhoAgendamento();
    iniciarRascunhoAgendamento(paciente);
    expect(recuperarRascunhoAgendamento(paciente)).toEqual({ filtros: dados.filtros, paciente: null });
  });
});
