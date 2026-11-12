import { Link } from 'react-router';
import { formatarPeriodo } from '../../utils/horario';
import Button from '../ui/Button';

const STATUS = { AGENDADA: 'agendada', CONFIRMADA: 'confirmada' };

/**
 * Aviso da #44: a mudança atinge consultas marcadas. Mostra quais são e deixa a secretaria
 * voltar e ajustar ou seguir mesmo assim (as consultas ficam como "Remarcar" no calendário).
 */
export default function ConflitoConsultas({ conflito, medicoId, acao, confirmando, onConfirmar, onCancelar }) {
  const { mensagem, consultas } = conflito;
  const primeiraData = consultas[0]?.inicio.slice(0, 10);
  return (
    <div className="conflito-consultas" role="alert">
      <p className="conflito-consultas__titulo">{mensagem}</p>
      <ul className="conflito-consultas__lista">
        {consultas.map((c) => (
          <li key={c.id}>
            <strong>{formatarPeriodo(c.inicio, c.fim)}</strong> · {c.pacienteNome ?? 'Paciente'}
            {c.especialidadeNome ? ` · ${c.especialidadeNome}` : ''} · {STATUS[c.status] ?? c.status}
          </li>
        ))}
      </ul>
      <p className="field__hint">
        Se seguir, as consultas continuam marcadas e aparecem como “Remarcar” no{' '}
        <Link to={`/calendario?medicoId=${medicoId}&data=${primeiraData}`}>calendário</Link>.
      </p>
      <footer className="form-actions">
        <Button variant="outline" onClick={onCancelar} disabled={confirmando}>Voltar e ajustar</Button>
        <Button variant="danger" onClick={onConfirmar} loading={confirmando}>{acao}</Button>
      </footer>
    </div>
  );
}
