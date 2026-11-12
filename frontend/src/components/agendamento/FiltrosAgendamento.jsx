import Alert from '../ui/Alert';
import Button from '../ui/Button';
import Card from '../ui/Card';
import Field from '../ui/Field';

// Necessita issue #21 para conectar os filtros à busca de horários.
export default function FiltrosAgendamento({ especialidades = [], medicos = [], value, onChange, loading = false, error, onRetry, disabled = false }) {
  const especialidadeId = especialidades.some((especialidade) => especialidade.id === value.especialidadeId)
    ? value.especialidadeId : '';
  const medicosDaEspecialidade = especialidadeId
    ? medicos.filter((medico) => medico.especialidadeIds.includes(especialidadeId)) : [];
  const medicoId = medicosDaEspecialidade.some((medico) => medico.id === value.medicoId) ? value.medicoId : '';
  const blocked = disabled || loading || Boolean(error);

  return (
    <Card title="Escolha o atendimento" description="Selecione a especialidade, o médico e a data da consulta.">
      {loading && <p role="status">Carregando especialidades e médicos…</p>}
      {error && <Alert action={onRetry && <Button variant="outline" size="sm" onClick={onRetry} disabled={disabled || loading}>Tentar novamente</Button>}>Não foi possível carregar as especialidades e os médicos. {error.message}</Alert>}
      <div className="form-grid">
        <Field as="select" name="especialidadeId" label="Especialidade" required value={especialidadeId} disabled={blocked || especialidades.length === 0}
          onChange={(event) => onChange({ ...value, especialidadeId: event.target.value, medicoId: '' })}>
          <option value="">Selecione uma especialidade</option>
          {especialidades.map((especialidade) => <option key={especialidade.id} value={especialidade.id}>{especialidade.nome}</option>)}
        </Field>
        <Field as="select" name="medicoId" label="Médico" required value={medicoId} disabled={blocked || !especialidadeId || medicosDaEspecialidade.length === 0}
          onChange={(event) => onChange({ ...value, especialidadeId, medicoId: event.target.value })}>
          <option value="">{especialidadeId ? 'Selecione um médico' : 'Selecione a especialidade primeiro'}</option>
          {medicosDaEspecialidade.map((medico) => <option key={medico.id} value={medico.id}>{medico.nome} — CRM {medico.crmNumero}/{medico.crmUf}</option>)}
        </Field>
        <Field type="date" name="data" label="Data da consulta" required value={value.data} disabled={blocked || !medicoId}
          onChange={(event) => onChange({ ...value, especialidadeId, medicoId, data: event.target.value })} />
      </div>
      {!loading && !error && especialidades.length === 0 && <p role="status">Nenhuma especialidade ativa encontrada.</p>}
      {!loading && !error && especialidadeId && medicosDaEspecialidade.length === 0 && <p role="status">Nenhum médico ativo nesta especialidade.</p>}
    </Card>
  );
}
