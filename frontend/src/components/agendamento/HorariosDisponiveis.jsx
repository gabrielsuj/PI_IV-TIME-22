import { useId } from 'react';
import { formatarHorarioAgendamento } from '../../utils/horarioAgendamento';
import Alert from '../ui/Alert';
import Button from '../ui/Button';
import Card from '../ui/Card';

function prepararHorarios(horarios, fusoHorario) {
  try {
    const vistos = new Set();
    return horarios.map((horario) => {
      const exibicao = formatarHorarioAgendamento(horario, fusoHorario);
      if (!exibicao || vistos.has(exibicao.inicio)) throw new Error('Horário inválido');
      vistos.add(exibicao.inicio);
      return { horario, inicio: exibicao.inicio, label: exibicao.periodo };
    }).sort((a, b) => a.inicio - b.inicio);
  } catch {
    return null;
  }
}

// Recebe as vagas e o fuso da agenda vindos de GET /vagas (#21).
export default function HorariosDisponiveis({ status = 'aguardando', horarios = [], fusoHorario, value, onChange, error, onRetry, disabled = false }) {
  const groupId = useId();
  const opcoes = status === 'sucesso' && horarios.length > 0 ? prepararHorarios(horarios, fusoHorario) : [];
  const erroDados = status === 'sucesso' && horarios.length > 0 && opcoes === null;
  const falha = status === 'erro' || erroDados;

  return (
    <Card title="Horários disponíveis" aria-busy={status === 'carregando' || undefined}>
      {status === 'aguardando' && <p role="status">Escolha a especialidade, o médico e a data para consultar os horários.</p>}
      {status === 'carregando' && <p role="status">Buscando horários disponíveis…</p>}
      {falha && <Alert action={onRetry && <Button variant="outline" size="sm" disabled={disabled} onClick={onRetry}>Tentar novamente</Button>}>
        {erroDados ? 'Não foi possível exibir os horários recebidos.' : `Não foi possível consultar os horários.${error?.message ? ` ${error.message}` : ''}`}
      </Alert>}
      {status === 'sem-agenda' && <p role="status">O médico ainda não possui agenda configurada.</p>}
      {status === 'sucesso' && horarios.length === 0 && <p role="status">Nenhum horário disponível nesta data. Escolha outra data ou outro médico.</p>}
      {status === 'sucesso' && !falha && opcoes.length > 0 && (
        <>
          <p className="field__hint">Horários no fuso {fusoHorario}.</p>
          <fieldset className="horarios-disponiveis" disabled={disabled || !onChange}>
            <legend className="sr-only">Selecione um horário</legend>
            {opcoes.map(({ horario, inicio, label }) => {
              const selected = value?.inicio === horario.inicio && value?.fim === horario.fim;
              return (
                <label key={inicio} className={`horario-opcao${selected ? ' horario-opcao--selecionado' : ''}`}>
                  <input type="radio" name={`horario-${groupId}`} value={horario.inicio} checked={selected} onChange={() => onChange(horario)} />
                  <span>{label}</span>
                </label>
              );
            })}
          </fieldset>
        </>
      )}
    </Card>
  );
}
