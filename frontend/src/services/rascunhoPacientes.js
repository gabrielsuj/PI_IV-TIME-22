// Somente memória desta aba. Nunca recebe senha/token nem grava dados em Web Storage.
const rascunhos = new Map();
let usuarioAtivo = null;
let usuarioSuspenso = null;

export function iniciarRascunhos(usuarioId) {
  if (!usuarioId) return;
  if ((usuarioAtivo || usuarioSuspenso) !== usuarioId) rascunhos.clear();
  usuarioAtivo = usuarioId;
  usuarioSuspenso = null;
}

export function suspenderRascunhos() {
  // Várias requisições da sessão antiga podem receber 401 antes do redirecionamento.
  if (usuarioAtivo === null) return;
  usuarioSuspenso = usuarioAtivo;
  usuarioAtivo = null;
  for (const rascunho of rascunhos.values()) rascunho.recuperavel = true;
}

export function encerrarRascunhos() {
  rascunhos.clear();
  usuarioAtivo = null;
  usuarioSuspenso = null;
}

export function guardarRascunho(usuarioId, rota, record, values) {
  if (!usuarioId || usuarioAtivo !== usuarioId) return;
  // Lista explícita de campos impede armazenar dados de login ou objetos completos da API.
  rascunhos.set(rota, {
    usuarioId, recuperavel: false,
    record: record ? { id: record.id || record._id, nome: values.nome, cpf: values.cpf, email: values.email } : null,
    values: { nome: values.nome, cpf: values.cpf, email: values.email },
  });
}

export function recuperarRascunho(usuarioId, rota) {
  const rascunho = rascunhos.get(rota);
  if (usuarioAtivo !== usuarioId || rascunho?.usuarioId !== usuarioId || !rascunho.recuperavel) return null;
  return { record: rascunho.record && { ...rascunho.record }, initialValues: { ...rascunho.values }, restored: true };
}

export function removerRascunho(usuarioId, rota) {
  if (rascunhos.get(rota)?.usuarioId === usuarioId) rascunhos.delete(rota);
}
