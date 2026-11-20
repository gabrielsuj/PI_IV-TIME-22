import { buscarAgendaDoDia, buscarProximasConsultas } from '../services/painelConsultasService';
import { useLeituraPainel } from './useLeituraPainel';

export function useAgendaDoDia(medicoId, dia) {
  return useLeituraPainel(buscarAgendaDoDia, { medicoId, dia }, Boolean(medicoId));
}

export function useProximasConsultas(medicoId) {
  return useLeituraPainel(buscarProximasConsultas, { medicoId });
}
