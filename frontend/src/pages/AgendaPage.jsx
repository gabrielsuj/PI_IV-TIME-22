import { useSearchParams } from 'react-router';
import BloqueiosCard from '../components/agenda/BloqueiosCard';
import JornadaForm from '../components/agenda/JornadaForm';
import { useAuth } from '../hooks/useAuth';
import { useRecords } from '../hooks/useRecords';
import Alert from '../components/ui/Alert';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';
import Field from '../components/ui/Field';
import PageHeader from '../components/ui/PageHeader';

export default function AgendaPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const { usuario } = useAuth();
  const agendaPropria = usuario.perfil === 'MEDICO';
  const medicoId = agendaPropria ? 'meu' : searchParams.get('medicoId') ?? '';
  const medicos = useRecords(agendaPropria ? null : 'medicos');
  const idDe = (medico) => medico.id || medico._id;
  const ativos = medicos.records.filter((medico) => medico.ativo !== false);
  const selecionavel = ativos.some((medico) => idDe(medico) === medicoId);

  return (
    <div className="page">
      <PageHeader title={agendaPropria ? 'Minha agenda' : 'Agenda'} description="Configure os dias e horários de atendimento, a duração das consultas e os períodos bloqueados de cada médico." />

      {!agendaPropria && (
        <Card className="search-card">
          <Field as="select" label="Médico" className="search-field" value={medicoId} disabled={medicos.loading || ativos.length === 0}
            onChange={(event) => setSearchParams(event.target.value ? { medicoId: event.target.value } : {})}>
            <option value="">Selecione um médico</option>
            {ativos.map((medico) => (
              <option key={idDe(medico)} value={idDe(medico)}>{medico.nome} — CRM {medico.crmNumero}/{medico.crmUf}</option>
            ))}
            {medicoId && !selecionavel && <option value={medicoId}>Médico {medicoId}</option>}
          </Field>
          {medicos.loading && <p role="status">Carregando lista de médicos…</p>}
          {medicos.error && (
            <Alert action={<Button variant="outline" size="sm" onClick={medicos.refresh}>Tentar novamente</Button>}>
              Não foi possível carregar a lista de médicos. {medicos.error.message}
            </Alert>
          )}
          {!medicos.loading && !medicos.error && ativos.length === 0 && (
            <p className="empty-state" role="status">Nenhum médico ativo disponível para seleção.</p>
          )}
        </Card>
      )}

      {medicoId ? (
        <div className="agenda-grid">
          <JornadaForm key={`jornada-${medicoId}`} medicoId={medicoId} />
          <BloqueiosCard key={`bloqueios-${medicoId}`} medicoId={medicoId} />
        </div>
      ) : (
        !medicos.loading && !medicos.error && ativos.length > 0 &&
          <Card><p className="empty-state" role="status">Selecione um médico para configurar a agenda.</p></Card>
      )}
    </div>
  );
}
