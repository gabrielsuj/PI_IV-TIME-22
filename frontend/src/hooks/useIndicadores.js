import { buscarIndicadores } from '../services/indicadoresService';
import { useLeituraPainel } from './useLeituraPainel';

export function useIndicadores({ de, ate, medicoId }) {
  return useLeituraPainel(buscarIndicadores, { de, ate, medicoId });
}
