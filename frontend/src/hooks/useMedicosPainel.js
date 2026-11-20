import { buscarMedicosPainel } from '../services/medicosPainelService';
import { useAuth } from './useAuth';
import { useLeituraPainel } from './useLeituraPainel';

export function useMedicosPainel() {
  const { usuario } = useAuth();
  const { dados, carregando, erro, atualizar } = useLeituraPainel(buscarMedicosPainel, {}, usuario?.perfil === 'SECRETARIA_ADMIN');
  return { records: dados ?? [], loading: carregando, error: erro, refresh: atualizar };
}
