import { useSearchParams } from 'react-router';
import BloqueiosCard from '../components/agenda/BloqueiosCard';
import JornadaForm from '../components/agenda/JornadaForm';
import { useRecords } from '../hooks/useRecords';
import Alert from '../components/ui/Alert';
import Card from '../components/ui/Card';
import Field from '../components/ui/Field';
import PageHeader from '../components/ui/PageHeader';

export default function AgendaPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const medicoId = searchParams.get('medicoId') ?? '';
  const medicos = useRecords('medicos');
  const idDe = (medico) => medico.id || medico._id;
  const ativos = medicos.records.filter((medico) => medico.ativo !== false);
  const conhecido = medicos.records.some((medico) => idDe(medico) === medicoId);

  return (
    <div className="page">
      <PageHeader title="Agenda" description="Configure os dias e horários de atendimento, a duração das consultas e os períodos bloqueados de cada médico." />

      <Card className="search-card">
        <Field as="select" label="Médico" className="search-field" value={medicoId} disabled={medicos.loading}
          onChange={(event) => setSearchParams(event.target.value ? { medicoId: event.target.value } : {})}>
          <option value="">{medicos.loading ? 'Carregando médicos…' : 'Selecione um médico'}</option>
          {ativos.map((medico) => (
            <option key={idDe(medico)} value={idDe(medico)}>{medico.nome} — CRM {medico.crmNumero}/{medico.crmUf}</option>
          ))}
          {medicoId && !conhecido && <option value={medicoId}>Médico {medicoId}</option>}
        </Field>
        {medicos.error && (
          <Alert tone="info">
            Não foi possível carregar a lista de médicos ({medicos.error.message}). A agenda ainda pode ser aberta pelo endereço <code>/agenda?medicoId=…</code>
          </Alert>
        )}
      </Card>

      {medicoId ? (
        <div className="agenda-grid">
          <JornadaForm key={`jornada-${medicoId}`} medicoId={medicoId} />
          <BloqueiosCard key={`bloqueios-${medicoId}`} medicoId={medicoId} />
        </div>
      ) : (
        <Card><p className="empty-state" role="status">Selecione um médico para configurar a agenda.</p></Card>
      )}
    </div>
  );
}
