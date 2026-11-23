/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { useId } from 'react';
import Alert from './Alert';
import Button from './Button';

export default function DataTable({
  caption,
  columns,
  rows = [],
  loading,
  error,
  onRetry,
  scrollHint,
  emptyMessage = 'Nenhum registro encontrado.',
  loadingMessage = 'Carregando registros…',
  getRowKey = (row) => row.id || row._id,
}) {
  const hintId = useId();
  // Oculta as linhas durante carregamento ou falha para não apresentar resultados
  // anteriores como se fossem a resposta atual.
  const exibidas = loading || error ? [] : rows;
  const acessoNegado = error?.status === 403;
  const tentarNovamente = error && !acessoNegado && onRetry && (
    <Button variant="outline" size="sm" onClick={onRetry}>Tentar novamente</Button>
  );

  return (
    <>
      {scrollHint && <p id={hintId} className="page__lead">{scrollHint}</p>}
      <div className="table-scroll" tabIndex={0} role="region" aria-label={caption}
        aria-describedby={scrollHint ? hintId : undefined} aria-busy={loading || undefined}>
        <table className="data-table">
          <caption className="sr-only">{caption}</caption>
          <thead>
            <tr>{columns.map((column) => <th key={column.key} scope="col" className={column.align === 'right' ? 'text-right' : undefined}>{column.label}</th>)}</tr>
          </thead>
          <tbody>
            {exibidas.map((row) => (
              <tr key={getRowKey(row)}>
                {columns.map((column) => <td key={column.key} className={column.align === 'right' ? 'text-right' : undefined}>{column.render ? column.render(row) : row[column.key] || '—'}</td>)}
              </tr>
            ))}
            {exibidas.length === 0 && (
              <tr><td colSpan={columns.length} className="data-table__empty">
                {loading ? <p role="status">{loadingMessage}</p> : error ? (
                  <Alert action={tentarNovamente}>
                    <p>{acessoNegado ? 'Acesso não permitido.' : 'Não foi possível carregar os registros.'}</p>
                    {error.message && <p>{error.message}</p>}
                  </Alert>
                ) : <p role="status">{emptyMessage}</p>}
              </td></tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}
