import { Link } from 'react-router';
import ApiStatus from '../components/ApiStatus';

export default function HomePage() {
  return (
    <div className="page">
      <header className="page__header">
        <h1>ConsultaFácil</h1>
        <p className="page__lead">
          Agendamento de consultas médicas com fila de espera automática: agenda centralizada,
          validação de conflitos e sinalização de vagas liberadas por cancelamentos.
        </p>
      </header>

      <ApiStatus />

      <p>
        Para ver os detalhes da conexão, acesse <Link to="/status">Status do sistema</Link>.
      </p>
    </div>
  );
}
