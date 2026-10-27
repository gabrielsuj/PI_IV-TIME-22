import { Link } from 'react-router';

export default function NotFoundPage() {
  return (
    <div className="page">
      <header className="page__header">
        <h1>Página não encontrada</h1>
        <p className="page__lead">O endereço acessado não existe ou foi alterado.</p>
      </header>
      <Link to="/" className="button">
        Voltar ao início
      </Link>
    </div>
  );
}
