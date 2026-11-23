/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { buscarAgendaDoDia, buscarProximasConsultas } from '../services/painelConsultasService';
import { useLeituraPainel } from './useLeituraPainel';

// A agenda aguarda a definição do médico; as próximas consultas podem usar o escopo
// geral autorizado pela sessão.
export function useAgendaDoDia(medicoId, dia) {
  return useLeituraPainel(buscarAgendaDoDia, { medicoId, dia }, Boolean(medicoId));
}

export function useProximasConsultas(medicoId) {
  return useLeituraPainel(buscarProximasConsultas, { medicoId });
}
