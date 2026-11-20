import { useLocation } from 'react-router';
import { useAuth } from '../../hooks/useAuth';
import { useAgendaDoDia } from '../../hooks/useConsultasPainel';
import { FUSO_CLINICA, STATUS_CONSULTA } from '../../utils/consulta';
import { origemPainel } from '../../utils/painel';
import Alert from '../ui/Alert';
import Badge from '../ui/Badge';
import Button from '../ui/Button';
import Card from '../ui/Card';
import DataTable from '../ui/DataTable';

const dataBr = (data) => data.split('-').reverse().join('/');
const horario = (valor, dia) => `${valor.slice(11)}${valor.slice(0, 10) === dia ? '' : ` (${dataBr(valor.slice(0, 10))})`}`;

export default function AgendaDiaPainel({ dia, medicoId: filtro, medicoInativo, verificandoMedico }) {
  const { usuario } = useAuth();
  const location = useLocation();
  const origem = origemPainel(location, usuario);
  const propria = usuario?.perfil === 'MEDICO';
  const medicoId = propria ? 'meu' : filtro;
  const podeConsultar = medicoId && !medicoInativo;
  const { permitido, carregando: buscandoAgenda, dados, erro, atualizar } = useAgendaDoDia(
    podeConsultar && !verificandoMedico ? medicoId : undefined, dia
  );
  const carregando = buscandoAgenda || verificandoMedico;
  if (!permitido) return null;

  const colunas = [
    { key: 'horario', label: 'Horário', render: (c) => `${horario(c.inicio, dia)} às ${horario(c.fim, dia)}` },
    { key: 'pacienteNome', label: 'Paciente' },
    { key: 'especialidadeNome', label: 'Especialidade' },
    { key: 'status', label: 'Status', render: (c) => <Badge tone={STATUS_CONSULTA[c.status].tom}>{STATUS_CONSULTA[c.status].rotulo}</Badge> },
    { key: 'acoes', label: 'Ações', align: 'right', render: (c) => <Button to={`/consultas/${encodeURIComponent(c.id)}`}
      state={origem} variant="outline" size="sm" aria-label={`Ver detalhes da consulta de ${c.pacienteNome || 'paciente não informado'}, ${horario(c.inicio, dia)}`}>
      Ver detalhes
    </Button> },
  ];

  return (
    <Card className="painel-consultas" title="Agenda do dia" aria-busy={carregando} description={`Atendimentos que ocorrem em ${dataBr(dia)} · ${FUSO_CLINICA}.`}
      action={podeConsultar && <Button variant="outline" onClick={atualizar} loading={carregando}>Atualizar agenda do dia</Button>}>
      {!medicoId && <p role="status">Selecione um médico no filtro do painel para consultar a agenda do dia.</p>}
      {medicoInativo && <p role="status">O médico selecionado está inativo. Selecione um médico ativo para consultar a agenda do dia.</p>}
      {podeConsultar && <>
        {dados && <p className="page__lead">Horários exibidos em {dados.fusoHorario}.</p>}
        {dados?.semAgenda && <Alert tone="info">Este médico ainda não tem agenda configurada. As consultas existentes continuam listadas.</Alert>}
        {erro ? <Alert action={<Button variant="outline" onClick={atualizar}>Tentar carregar agenda novamente</Button>}>{erro.message}</Alert>
          : <DataTable caption="Consultas na agenda do dia" columns={colunas} rows={dados?.consultas ?? []}
            scrollHint="Para ver todas as colunas, deslize a tabela para os lados ou coloque-a em foco e use as setas."
            loading={carregando} loadingMessage="Carregando agenda do dia…" emptyMessage="Nenhuma consulta na agenda deste dia." />}
      </>}
    </Card>
  );
}
