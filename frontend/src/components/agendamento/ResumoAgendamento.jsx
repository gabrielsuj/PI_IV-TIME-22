import { formatarHorarioAgendamento } from '../../utils/horarioAgendamento';
import Alert from '../ui/Alert';
import Button from '../ui/Button';
import Card from '../ui/Card';

// Necessita issue #21 para fornecer a confirmação real do agendamento.
export default function ResumoAgendamento({ usuario, paciente, especialidade, medico, horario, fusoHorario, onVoltar, onConfirmar, confirmando = false, error }) {
  const pessoal = usuario?.perfil === 'PACIENTE';
  const permitido = pessoal || usuario?.perfil === 'SECRETARIA_ADMIN';
  const exibicao = formatarHorarioAgendamento(horario, fusoHorario);
  const nomePaciente = pessoal ? usuario.nome : paciente?.nome;
  const valido = permitido && Boolean(nomePaciente && (pessoal || paciente?.id) && especialidade?.id && especialidade.ativa === true
    && medico?.id && medico.ativo === true && medico.especialidadeIds?.includes(especialidade.id) && exibicao);
  const conflito = error?.status === 409;
  const podeConfirmar = valido && !confirmando && !conflito && typeof onConfirmar === 'function';

  function confirmar(event) {
    event.preventDefault();
    if (podeConfirmar) onConfirmar();
  }

  return (
    <Card title="Revise sua consulta" description="Confira os dados antes de confirmar o agendamento." aria-busy={confirmando || undefined}>
      <form onSubmit={confirmar}>
        {!permitido ? <Alert>Seu perfil não permite agendar consultas.</Alert>
          : !valido ? <Alert>Revise os dados e selecione um horário antes de confirmar.</Alert> : (
            <dl className="resumo-agendamento">
              <div><dt>Paciente</dt><dd>{nomePaciente}{pessoal ? ' (você)' : ''}</dd></div>
              {!pessoal && paciente.cpf && <div><dt>CPF</dt><dd>{paciente.cpf}</dd></div>}
              <div><dt>Especialidade</dt><dd>{especialidade.nome}</dd></div>
              <div><dt>Médico</dt><dd>{medico.nome}</dd></div>
              <div><dt>CRM</dt><dd>{medico.crmNumero}/{medico.crmUf}</dd></div>
              <div><dt>Data</dt><dd>{exibicao.data}</dd></div>
              <div><dt>Horário</dt><dd>{exibicao.periodo}</dd></div>
              <div><dt>Fuso horário</dt><dd>{fusoHorario}</dd></div>
            </dl>
          )}
        {error && !confirmando && <Alert>{conflito
          ? 'Este horário não está mais disponível. Volte para escolher outro horário.'
          : error.message || 'Não foi possível confirmar o agendamento. Tente novamente.'}</Alert>}
        <div className="form-actions">
          <Button variant="outline" onClick={onVoltar} disabled={confirmando || typeof onVoltar !== 'function'}>
            {conflito ? 'Escolher outro horário' : 'Voltar para os horários'}
          </Button>
          <Button type="submit" loading={confirmando} disabled={!podeConfirmar}>{confirmando ? 'Confirmando…' : 'Confirmar agendamento'}</Button>
        </div>
      </form>
    </Card>
  );
}
