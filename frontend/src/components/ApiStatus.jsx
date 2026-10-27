import { useApiHealth } from '../hooks/useApiHealth';
import { API_BASE_URL } from '../services/api';

const LABELS = {
  loading: 'Verificando…',
  online: 'API disponível',
  offline: 'API indisponível',
};

/**
 * Exibe o resultado do GET /health.
 *
 * @param {object} props
 * @param {boolean} [props.detailed] mostra URL, latência e resposta completa
 */
export default function ApiStatus({ detailed = false }) {
  const { status, data, error, latencyMs, checkedAt, refresh } = useApiHealth();

  return (
    <section className="card api-status" aria-labelledby="api-status-title">
      <div className="api-status__header">
        <h2 id="api-status-title" className="card__title">
          Conexão com a API
        </h2>
        <span className={`badge badge--${status}`} role="status">
          {LABELS[status]}
        </span>
      </div>

      {status === 'offline' && error && <p className="api-status__error">{error.message}</p>}

      {detailed && (
        <dl className="api-status__details">
          <dt>Endereço</dt>
          <dd>
            <code>{API_BASE_URL}/health</code>
          </dd>
          {latencyMs !== null && (
            <>
              <dt>Tempo de resposta</dt>
              <dd>{latencyMs} ms</dd>
            </>
          )}
          {error?.status > 0 && (
            <>
              <dt>Código HTTP</dt>
              <dd>{error.status}</dd>
            </>
          )}
          {checkedAt && (
            <>
              <dt>Última verificação</dt>
              <dd>{checkedAt.toLocaleTimeString('pt-BR')}</dd>
            </>
          )}
        </dl>
      )}

      {detailed && data !== null && (
        <pre className="api-status__payload" aria-label="Resposta da API">
          {typeof data === 'string' ? data : JSON.stringify(data, null, 2)}
        </pre>
      )}

      <button type="button" className="button" onClick={refresh} disabled={status === 'loading'}>
        Verificar novamente
      </button>
    </section>
  );
}
