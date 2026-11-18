import { useEffect, useState } from 'react';

/** Atualiza a visibilidade das ações quando o fim previsto chega, mesmo com a tela aberta. */
export function usePrazoConsulta(fim) {
  const [agora, setAgora] = useState(Date.now);
  useEffect(() => {
    const instante = Date.parse(fim);
    if (!Number.isFinite(instante) || instante <= agora) return;
    const espera = Math.max(1, Math.min(instante - Date.now(), 2_147_483_647));
    const timer = setTimeout(() => setAgora(Date.now()), espera);
    return () => clearTimeout(timer);
  }, [fim, agora]);
  return agora;
}
