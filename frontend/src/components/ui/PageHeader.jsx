/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

// Mantém o título principal em h1; seções internas podem usar níveis inferiores sem
// disputar a identificação da página.
export default function PageHeader({ title, description, action }) {
  return (
    <header className="page__header">
      <div>
        <h1>{title}</h1>
        {description && <p className="page__lead">{description}</p>}
      </div>
      {action}
    </header>
  );
}
