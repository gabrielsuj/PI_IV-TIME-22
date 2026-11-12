import { useId } from 'react';
import Alert from '../ui/Alert';
import Button from '../ui/Button';
import Card from '../ui/Card';

function prepararHorarios(horarios, fusoHorario) {
  try {
    if (!fusoHorario) return null;
    const hora = new Intl.DateTimeFormat('pt-BR', { timeZone: fusoHorario, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
    const dia = new Intl.DateTimeFormat('pt-BR', { timeZone: fusoHorario, dateStyle: 'short' });
    const vistos = new Set();
    return horarios.map((horario) => {
      // Instantes precisam trazer Z ou offset; nunca interpretar a vaga no fuso do navegador.
      const instante = (valor) => typeof valor === 'string' && /(?:Z|[+-]\d{2}:\d{2})$/.test(valor) ? Date.parse(valor) : NaN;
      const inicio = instante(horario.inicio);
      const fim = instante(horario.fim);
      if (!Number.isFinite(inicio) || !Number.isFinite(fim) || inicio >= fim || vistos.has(inicio)) throw new Error('Horário inválido');
      vistos.add(inicio);
      const fimExibido = `${hora.format(fim)}${dia.format(inicio) === dia.format(fim) ? '' : ` (${dia.format(fim)})`}`;
      return { horario, inicio, label: `${hora.format(inicio)} às ${fimExibido}` };
    }).sort((a, b) => a.inicio - b.inicio);
  } catch {
    return null;
  }
}

// Necessita issue #21 para receber as vagas reais e o fuso da agenda.
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
