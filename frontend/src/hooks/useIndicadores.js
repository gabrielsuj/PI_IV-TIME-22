/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { buscarIndicadores } from '../services/indicadoresService';
import { useLeituraPainel } from './useLeituraPainel';

export function useIndicadores({ de, ate, medicoId }) {
  // Entrega período e médico à leitura compartilhada para que mudança de filtro
  // invalide o resultado anterior.
  return useLeituraPainel(buscarIndicadores, { de, ate, medicoId });
}
