import { useCallback, useEffect, useState } from 'react';
import { api } from '../services/api';

export function useRecords(resource) {
  const [records, setRecords] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [revision, setRevision] = useState(0);
  const refresh = useCallback(() => setRevision((value) => value + 1), []);

  useEffect(() => {
    const controller = new AbortController();
    api.get(`/${resource}`, { signal: controller.signal })
      .then((data) => {
        const items = Array.isArray(data) ? data : data?.items;
        if (!Array.isArray(items)) throw new Error('Não foi possível ler os cadastros. Tente novamente.');
        setRecords(items);
        setError(null);
      })
      .catch((failure) => { if (!controller.signal.aborted) setError(failure); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [resource, revision]);

  const retry = useCallback(() => { setLoading(true); setError(null); refresh(); }, [refresh]);

  return { records, loading, error, refresh: retry };
}
