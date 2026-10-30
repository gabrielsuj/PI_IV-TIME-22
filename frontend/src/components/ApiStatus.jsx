import { useApiHealth } from '../hooks/useApiHealth';
import { API_BASE_URL } from '../services/api';
import Badge from './ui/Badge';
import Button from './ui/Button';
import Card from './ui/Card';

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
    <Card className="api-status" title="Conexão com a API" action={<Badge status={status} role="status">{LABELS[status]}</Badge>}>

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

      <Button onClick={refresh} loading={status === 'loading'}>
        Verificar novamente
      </Button>
    </Card>
  );
}
