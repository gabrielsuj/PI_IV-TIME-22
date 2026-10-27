import ApiStatus from '../components/ApiStatus';

export default function StatusPage() {
  return (
    <div className="page">
      <header className="page__header">
        <h1>Status do sistema</h1>
        <p className="page__lead">Resultado da verificação do endpoint de saúde da API.</p>
      </header>

      <ApiStatus detailed />
    </div>
  );
}
