/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { useAuth } from '../../hooks/useAuth';
import { useProximasConsultas } from '../../hooks/useConsultasPainel';
import { LIMITE_LISTAGEM } from '../../services/consultasService';
import { LIMITE_PROXIMAS_PAINEL } from '../../services/painelConsultasService';
import { dataHoraDaConsulta, FUSO_CLINICA, STATUS_CONSULTA } from '../../utils/consulta';
import { origemPainel } from '../../utils/painel';
import Alert from '../ui/Alert';
import Badge from '../ui/Badge';
import Button from '../ui/Button';
import Card from '../ui/Card';
import DataTable from '../ui/DataTable';

export default function ProximasConsultasPainel({ medicoId }) {
  const { usuario } = useAuth();
  const location = useLocation();
  const origem = origemPainel(location, usuario);
  const { permitido, carregando, dados, erro, atualizar } = useProximasConsultas(medicoId);
  if (!permitido) return null;

  // O resumo exibe as primeiras consultas retornadas pelo serviço e mantém o acesso à
  // listagem para consultar os demais registros.
  const colunas = [
    { key: 'data', label: 'Data', render: (c) => dataHoraDaConsulta(c).data },
    { key: 'horario', label: 'Horário', render: (c) => dataHoraDaConsulta(c).periodo },
    { key: 'pacienteNome', label: 'Paciente' },
    usuario?.perfil !== 'MEDICO' && { key: 'medicoNome', label: 'Médico' },
    { key: 'especialidadeNome', label: 'Especialidade' },
    { key: 'status', label: 'Status', render: (c) => <Badge tone={STATUS_CONSULTA[c.status].tom}>{STATUS_CONSULTA[c.status].rotulo}</Badge> },
    { key: 'acoes', label: 'Ações', align: 'right', render: (c) => <Button to={`/consultas/${encodeURIComponent(c.id)}`}
      state={origem} variant="outline" size="sm"
      aria-label={`Ver detalhes da consulta de ${c.pacienteNome || 'paciente não informado'}, ${dataHoraDaConsulta(c).data}, ${dataHoraDaConsulta(c).periodo}`}>
      Ver detalhes
    </Button> },
  ].filter(Boolean);

  return (
    <Card className="painel-consultas" title="Próximas consultas" aria-busy={carregando}
      description={`Até ${LIMITE_PROXIMAS_PAINEL} consultas em andamento ou futuras a partir de agora, em ordem de início · ${FUSO_CLINICA}.`}
      action={<Button variant="outline" onClick={atualizar} loading={carregando}>Atualizar próximas consultas</Button>}>
      {erro ? <Alert action={<Button variant="outline" onClick={atualizar}>Tentar carregar próximas consultas novamente</Button>}>{erro.message}</Alert>
        : <DataTable caption="Resumo das próximas consultas" columns={colunas} rows={dados?.consultas ?? []}
          scrollHint="Para ver todas as colunas, deslize a tabela para os lados ou coloque-a em foco e use as setas."
          loading={carregando} loadingMessage="Carregando próximas consultas…" emptyMessage="Nenhuma próxima consulta encontrada." />}
      {dados && dados.totalRecebido > LIMITE_PROXIMAS_PAINEL && <p className="page__lead">
        Mostrando {dados.consultas.length} de {dados.totalRecebido} consultas recebidas.
      </p>}
      {dados?.limiteAtingido && <Alert tone="info">
        A listagem está limitada às primeiras {LIMITE_LISTAGEM} consultas. Use os filtros na página de consultas para encontrar outras.
      </Alert>}
      <Button to={medicoId ? `/consultas?medicoId=${encodeURIComponent(medicoId)}` : '/consultas'} state={origem} variant="outline">Abrir lista de consultas</Button>
    </Card>
  );
}
import { useLocation } from 'react-router';
