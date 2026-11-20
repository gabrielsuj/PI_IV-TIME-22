import { useId } from 'react';
import Button from './Button';

export default function DataTable({ caption, columns, rows = [], loading, error, onRetry, scrollHint, emptyMessage = 'Nenhum registro encontrado.', loadingMessage = 'Carregando registros…', getRowKey = (row) => row.id || row._id }) {
  const hintId = useId();
  const message = loading ? loadingMessage : error ? 'Não foi possível carregar os registros.' : emptyMessage;

  return (
    <>
    {scrollHint && <p id={hintId} className="page__lead">{scrollHint}</p>}
    <div className="table-scroll" tabIndex={0} role="region" aria-label={caption} aria-describedby={scrollHint ? hintId : undefined} aria-busy={loading || undefined}>
      <table className="data-table">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr>{columns.map((column) => <th key={column.key} scope="col" className={column.align === 'right' ? 'text-right' : undefined}>{column.label}</th>)}</tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={getRowKey(row)}>
              {columns.map((column) => <td key={column.key} className={column.align === 'right' ? 'text-right' : undefined}>{column.render ? column.render(row) : row[column.key] || '—'}</td>)}
            </tr>
          ))}
          {(loading || error || rows.length === 0) && (
            <tr><td colSpan={columns.length} className="data-table__empty">
              <p role={error ? 'alert' : 'status'}>{message}</p>
              {error && onRetry && <Button variant="outline" size="sm" onClick={onRetry}>Tentar novamente</Button>}
            </td></tr>
          )}
        </tbody>
      </table>
    </div>
    </>
  );
}
