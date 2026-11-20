import { useState } from 'react';
import { useAuth } from '../../hooks/useAuth';
import { useAgendaDoDia } from '../../hooks/useConsultasPainel';
import { useRecords } from '../../hooks/useRecords';
import { STATUS_CONSULTA } from '../../utils/consulta';
import Alert from '../ui/Alert';
import Badge from '../ui/Badge';
import Button from '../ui/Button';
import Card from '../ui/Card';
import DataTable from '../ui/DataTable';
import Field from '../ui/Field';

const dataBr = (data) => data.split('-').reverse().join('/');
const horario = (valor, dia) => `${valor.slice(11)}${valor.slice(0, 10) === dia ? '' : ` (${dataBr(valor.slice(0, 10))})`}`;

export default function AgendaDiaPainel({ dia }) {
  const { usuario } = useAuth();
  const propria = usuario?.perfil === 'MEDICO';
  const [selecionado, setSelecionado] = useState('');
  const medicos = useRecords(usuario?.perfil === 'SECRETARIA_ADMIN' ? 'medicos' : null);
  const medicoId = propria ? 'meu' : selecionado;
  const { permitido, carregando, dados, erro, atualizar } = useAgendaDoDia(medicoId, dia);
  if (!permitido) return null;

  const colunas = [
    { key: 'horario', label: 'Horário', render: (c) => `${horario(c.inicio, dia)} às ${horario(c.fim, dia)}` },
    { key: 'pacienteNome', label: 'Paciente' },
    { key: 'especialidadeNome', label: 'Especialidade' },
    { key: 'status', label: 'Status', render: (c) => <Badge tone={STATUS_CONSULTA[c.status].tom}>{STATUS_CONSULTA[c.status].rotulo}</Badge> },
  ];

  return (
    <Card className="painel-consultas" title="Agenda do dia" description={`${dataBr(dia)} · Horários no fuso da agenda do médico.`}
      action={medicoId && <Button variant="outline" onClick={atualizar} loading={carregando}>Atualizar agenda do dia</Button>}>
      {!propria && <Field as="select" label="Médico da agenda do dia" value={selecionado}
        disabled={medicos.loading || Boolean(medicos.error)} onChange={(event) => setSelecionado(event.target.value)}>
        <option value="">{medicos.loading ? 'Carregando médicos…' : 'Selecione um médico'}</option>
        {medicos.records.filter((medico) => medico.ativo !== false).map((medico) => (
          <option key={medico.id || medico._id} value={medico.id || medico._id}>{medico.nome}</option>
        ))}
      </Field>}
      {!propria && medicos.error && <Alert action={<Button variant="outline" onClick={medicos.refresh}>Tentar carregar médicos novamente</Button>}>
        Não foi possível carregar os médicos. {medicos.error.message}
      </Alert>}
      {!medicoId && !medicos.error && <p role="status">Selecione um médico para consultar a agenda do dia.</p>}
      {medicoId && <>
        {dados && <p className="page__lead">Fuso da agenda: {dados.fusoHorario}.</p>}
        {dados?.semAgenda && <Alert tone="info">Este médico ainda não tem agenda configurada. As consultas existentes continuam listadas.</Alert>}
        {erro ? <Alert action={<Button variant="outline" onClick={atualizar}>Tentar carregar agenda novamente</Button>}>{erro.message}</Alert>
          : <DataTable caption="Consultas na agenda do dia" columns={colunas} rows={dados?.consultas ?? []}
            loading={carregando} emptyMessage="Nenhuma consulta na agenda deste dia." />}
      </>}
    </Card>
  );
}
