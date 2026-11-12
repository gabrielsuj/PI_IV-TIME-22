import { api } from './api';

export async function buscarCatalogosAgendamento({ signal } = {}) {
  const [especialidades, medicos] = await Promise.all([
    api.get('/especialidades', { signal }),
    api.get('/medicos', { signal }),
  ]);

  if (!Array.isArray(especialidades) || !Array.isArray(medicos)) {
    throw new Error('Não foi possível ler as especialidades e os médicos. Tente novamente.');
  }

  return {
    especialidades: especialidades.filter((especialidade) => especialidade.ativa === true),
    medicos: medicos.filter((medico) => medico.ativo === true),
  };
}
