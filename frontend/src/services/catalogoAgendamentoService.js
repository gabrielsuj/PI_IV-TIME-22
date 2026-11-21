/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

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
    // Oferece cadastros ativos no novo agendamento, preservando os identificadores
    // recebidos para a confirmação na API.
    especialidades: especialidades.filter((especialidade) => especialidade.ativa === true),
    medicos: medicos.filter((medico) => medico.ativo === true),
  };
}
