import { isRouteErrorResponse, Link, useRouteError } from 'react-router';

/** Exibida quando uma rota lança um erro inesperado durante a renderização. */
export default function ErrorPage() {
  const error = useRouteError();
  const detail = isRouteErrorResponse(error)
    ? `${error.status} ${error.statusText}`
    : error instanceof Error
      ? error.message
      : null;

  return (
    <div className="page page--standalone">
      <header className="page__header">
        <h1>Algo deu errado</h1>
        <p className="page__lead">Ocorreu um erro inesperado ao exibir esta tela.</p>
      </header>
      {detail && (
        <pre className="api-status__payload">
          <code>{detail}</code>
        </pre>
      )}
      <Link to="/" className="button">
        Voltar ao início
      </Link>
    </div>
  );
}
