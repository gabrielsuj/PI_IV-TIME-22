// Somente memória desta aba; horários, senha e token não fazem parte do rascunho.
let usuarioAtivo = null;
let usuarioSuspenso = null;
let rascunho = null;

function identidade(usuario) {
  return usuario?.id && ['SECRETARIA_ADMIN', 'PACIENTE'].includes(usuario.perfil)
    ? `${usuario.perfil}:${usuario.id}` : null;
}

export function iniciarRascunhoAgendamento(usuario) {
  const chave = identidade(usuario);
  if (!chave || (usuarioAtivo || usuarioSuspenso) !== chave) encerrarRascunhoAgendamento();
  usuarioAtivo = chave;
  usuarioSuspenso = null;
}

export function suspenderRascunhoAgendamento() {
  if (!usuarioAtivo) return;
  usuarioSuspenso = usuarioAtivo;
  usuarioAtivo = null;
  if (rascunho) rascunho.recuperavel = true;
}

export function encerrarRascunhoAgendamento() {
  rascunho = null;
  usuarioAtivo = null;
  usuarioSuspenso = null;
}

export function guardarRascunhoAgendamento(usuario, { filtros, paciente }) {
  if (!usuarioAtivo || usuarioAtivo !== identidade(usuario)) return;
  if (!paciente?.id && !filtros.especialidadeId && !filtros.medicoId && !filtros.data) {
    rascunho = null;
    return;
  }
  rascunho = {
    recuperavel: false,
    filtros: { especialidadeId: filtros.especialidadeId, medicoId: filtros.medicoId, data: filtros.data },
    paciente: usuario.perfil === 'SECRETARIA_ADMIN' && paciente?.id
      ? { id: paciente.id, nome: paciente.nome, cpf: paciente.cpf } : null,
  };
}

export function recuperarRascunhoAgendamento(usuario) {
  if (!usuarioAtivo || usuarioAtivo !== identidade(usuario) || !rascunho?.recuperavel) return null;
  return { filtros: { ...rascunho.filtros }, paciente: rascunho.paciente && { ...rascunho.paciente } };
}

export function removerRascunhoAgendamento(usuario) {
  // Ao desmontar por 401, o contexto já está suspenso e aguarda a mesma conta.
  if (usuarioAtivo && usuarioAtivo === identidade(usuario)) rascunho = null;
}
