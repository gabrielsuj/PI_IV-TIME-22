/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { buscarMedicosPainel } from '../services/medicosPainelService';
import { useAuth } from './useAuth';
import { useLeituraPainel } from './useLeituraPainel';

export function useMedicosPainel() {
  const { usuario } = useAuth();
  // O catálogo é carregado para a secretaria, que precisa escolher um médico; o perfil
  // médico utiliza o próprio vínculo.
  const { dados, carregando, carregamentoInicial, erro, atualizar } = useLeituraPainel(buscarMedicosPainel, {}, usuario?.perfil === 'SECRETARIA_ADMIN');
  return { records: dados ?? [], loading: carregando, carregamentoInicial, error: erro, refresh: atualizar };
}
