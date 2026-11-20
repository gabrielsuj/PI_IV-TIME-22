import { api } from './api';

export async function buscarMedicosPainel(_filtros, { signal } = {}) {
  const registros = await api.get('/medicos', { signal });
  const ids = new Set();
  if (!Array.isArray(registros)) throw new Error('Não foi possível ler a lista de médicos recebida. Tente novamente.');
  return registros.map((medico) => {
    const id = medico?.id || medico?._id;
    if (typeof id !== 'string' || !/^[a-f\d]{24}$/i.test(id) || ids.has(id.toLowerCase())
      || typeof medico.nome !== 'string' || !medico.nome.trim()
      || medico.ativo != null && typeof medico.ativo !== 'boolean')
      throw new Error('Não foi possível ler a lista de médicos recebida. Tente novamente.');
    ids.add(id.toLowerCase());
    return { id: id.toLowerCase(), nome: medico.nome, ativo: medico.ativo };
  });
}
