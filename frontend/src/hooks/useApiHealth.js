import { useCallback, useEffect, useState } from 'react';
import { getHealth } from '../services/healthService';

const INITIAL_STATE = {
  status: 'loading',
  data: null,
  error: null,
  latencyMs: null,
  checkedAt: null,
};

/**
 * Verifica a disponibilidade da API ao montar o componente.
 *
 * @returns {{
 *   status: 'loading' | 'online' | 'offline',
 *   data: unknown,
 *   error: import('../services/api').ApiError | null,
 *   latencyMs: number | null,
 *   checkedAt: Date | null,
 *   refresh: () => void,
 * }}
 */
export function useApiHealth() {
  const [state, setState] = useState(INITIAL_STATE);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    const startedAt = performance.now();

    getHealth({ signal: controller.signal })
      .then((data) => {
        setState({
          status: 'online',
          data,
          error: null,
          latencyMs: Math.round(performance.now() - startedAt),
          checkedAt: new Date(),
        });
      })
      .catch((error) => {
        if (controller.signal.aborted) return;
        setState({
          status: 'offline',
          data: null,
          error,
          latencyMs: null,
          checkedAt: new Date(),
        });
      });

    return () => controller.abort();
  }, [attempt]);

  const refresh = useCallback(() => {
    setState((previous) => ({ ...previous, status: 'loading' }));
    setAttempt((count) => count + 1);
  }, []);

  return { ...state, refresh };
}
