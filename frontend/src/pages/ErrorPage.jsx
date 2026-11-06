import { isRouteErrorResponse, useRouteError } from 'react-router';
import Button from '../components/ui/Button';
import PageHeader from '../components/ui/PageHeader';

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
      <PageHeader title="Algo deu errado" description="Ocorreu um erro inesperado ao exibir esta tela." />
      {detail && (
        <pre className="api-status__payload">
          <code>{detail}</code>
        </pre>
      )}
      <div><Button to="/">
        Voltar ao início
      </Button></div>
    </div>
  );
}
