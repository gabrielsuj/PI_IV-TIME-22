/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { isRouteErrorResponse, useRouteError } from 'react-router';
import Button from '../components/ui/Button';
import PageHeader from '../components/ui/PageHeader';

/** Exibida quando uma rota lança um erro inesperado durante a renderização. */
export default function ErrorPage() {
  const error = useRouteError();
  // Erros de rota e exceções de renderização têm formatos diferentes; o detalhe
  // disponível acompanha a orientação de retorno ao início.
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
