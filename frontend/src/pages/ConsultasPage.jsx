/*
Autoria: Murilo Moraes
RA: 25000073
*/
/*
Coautoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { useEffect, useState } from 'react';
import { useLocation, useSearchParams } from 'react-router';
import Alert from '../components/ui/Alert';
import Badge from '../components/ui/Badge';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';
import DataTable from '../components/ui/DataTable';
import Field from '../components/ui/Field';
import PageHeader from '../components/ui/PageHeader';
import { useAuth } from '../hooks/useAuth';
import { useRecords } from '../hooks/useRecords';
import { LIMITE_LISTAGEM, listarConsultas } from '../services/consultasService';
import { dataHoraDaConsulta, STATUS_CONSULTA } from '../utils/consulta';
import { origemPainel, retornoPainel } from '../utils/painel';

const idDe = (registro) => registro.id || registro._id;

const TEXTOS = {
  PACIENTE: { titulo: 'Minhas consultas', descricao: 'Suas consultas marcadas e o histórico de atendimentos.' },
  MEDICO: { titulo: 'Minhas consultas', descricao: 'Consultas em que você atende, com os dados básicos de cada paciente.' },
  SECRETARIA_ADMIN: { titulo: 'Consultas', descricao: 'Todas as consultas da clínica. Use os filtros para encontrar uma consulta.' },
};

/** Consultas do período escolhido, recarregadas quando os filtros mudam. */
function useConsultas(filtros) {
  const chave = JSON.stringify(filtros);
  const [revisao, setRevisao] = useState(0);
  const [estado, setEstado] = useState({ chave: null, consultas: [], erro: null });
  const chaveAtual = `${chave}|${revisao}`;

  useEffect(() => {
    const controller = new AbortController();
    listarConsultas(JSON.parse(chave), { signal: controller.signal })
      .then((consultas) => {
        if (controller.signal.aborted) return;
        if (!Array.isArray(consultas)) throw new Error('Não foi possível ler a lista de consultas.');
        setEstado({ chave: chaveAtual, consultas, erro: null });
      })
      .catch((erro) => { if (!controller.signal.aborted) setEstado({ chave: chaveAtual, consultas: [], erro }); });
    return () => controller.abort();
  }, [chave, chaveAtual]);

  return { ...estado, carregando: estado.chave !== chaveAtual, recarregar: () => setRevisao((r) => r + 1) };
}

export default function ConsultasPage() {
  const { usuario } = useAuth();
  const location = useLocation();
  const origem = origemPainel(location, usuario);
  // Mantém o caminho de volta ao painel com os filtros da conta atual, sem interferir
  // nos filtros próprios da listagem.
  const retorno = retornoPainel(location, usuario);
  const perfil = usuario?.perfil;
  const [params, setParams] = useSearchParams();
  const periodo = params.get('periodo') === 'anteriores' ? 'anteriores' : 'proximas';
  const status = STATUS_CONSULTA[params.get('status')] ? params.get('status') : '';
  const medicoId = perfil === 'MEDICO' ? '' : params.get('medicoId') ?? '';

  const lista = useConsultas({ periodo, status, medicoId });
  const mostraFiltroMedico = perfil !== 'MEDICO';
  const medicos = useRecords(mostraFiltroMedico ? 'medicos' : null);
  const medicoSelecionadoForaDaLista = medicoId && !medicos.records.some((medico) => idDe(medico) === medicoId);
  const textos = TEXTOS[perfil] ?? TEXTOS.SECRETARIA_ADMIN;

  const filtrar = (mudancas) => {
    const proximo = { periodo, status, medicoId, ...mudancas };
    setParams(Object.fromEntries(Object.entries(proximo).filter(([chave, valor]) => valor && !(chave === 'periodo' && valor === 'proximas'))), { state: origem });
  };

  const colunas = [
    { key: 'data', label: 'Data', render: (c) => dataHoraDaConsulta(c).data },
    { key: 'horario', label: 'Horário', render: (c) => dataHoraDaConsulta(c).periodo },
    perfil !== 'PACIENTE' && { key: 'pacienteNome', label: 'Paciente' },
    perfil !== 'MEDICO' && { key: 'medicoNome', label: 'Médico' },
    { key: 'especialidadeNome', label: 'Especialidade' },
    {
      key: 'status', label: 'Status',
      render: (c) => <Badge tone={STATUS_CONSULTA[c.status]?.tom}>{STATUS_CONSULTA[c.status]?.rotulo ?? c.status}</Badge>,
    },
    {
      key: 'acoes', label: 'Ações', align: 'right',
      render: (c) => (
        <Button to={`/consultas/${encodeURIComponent(c.id)}`} state={origem} variant="outline" size="sm"
          aria-label={`Ver detalhes da consulta de ${dataHoraDaConsulta(c).data}, ${dataHoraDaConsulta(c).periodo}`}>
          Ver detalhes
        </Button>
      ),
    },
  ].filter(Boolean);

  const vazio = periodo === 'proximas' ? 'Nenhuma consulta marcada com estes filtros.' : 'Nenhuma consulta anterior com estes filtros.';

  return (
    <div className="page">
      <PageHeader title={textos.titulo} description={textos.descricao}
        action={(retorno || perfil !== 'MEDICO') && <div className="cal-nav">
          {retorno && <Button to={retorno} variant="outline">‹ Voltar ao painel</Button>}
          {perfil !== 'MEDICO' && <Button to="/nova-consulta" state={origem}>Nova consulta</Button>}
        </div>} />

      <Card className="search-card">
        <div className="cal-controles">
          <div className="cal-nav" role="group" aria-label="Período">
            <Button variant={periodo === 'proximas' ? 'primary' : 'outline'} size="sm" aria-pressed={periodo === 'proximas'}
              onClick={() => filtrar({ periodo: 'proximas' })}>Próximas</Button>
            <Button variant={periodo === 'anteriores' ? 'primary' : 'outline'} size="sm" aria-pressed={periodo === 'anteriores'}
              onClick={() => filtrar({ periodo: 'anteriores' })}>Anteriores</Button>
          </div>
          <Field as="select" label="Status" value={status} onChange={(event) => filtrar({ status: event.target.value })}>
            <option value="">Todos</option>
            {Object.entries(STATUS_CONSULTA).map(([valor, { rotulo }]) => <option key={valor} value={valor}>{rotulo}</option>)}
          </Field>
          {mostraFiltroMedico && (
            <Field as="select" label="Médico" value={medicoId} disabled={medicos.loading || Boolean(medicos.error)}
              onChange={(event) => filtrar({ medicoId: event.target.value })}>
              <option value="">{medicos.loading ? 'Carregando médicos…' : medicos.error ? 'Médicos indisponíveis' : 'Todos'}</option>
              {medicos.records.map((m) => <option key={idDe(m)} value={idDe(m)}>{m.nome}</option>)}
              {medicoSelecionadoForaDaLista && <option value={medicoId}>Médico selecionado</option>}
            </Field>
          )}
        </div>
        {mostraFiltroMedico && medicos.error && (
          <Alert action={medicos.error.status !== 403 && (
            <Button variant="outline" size="sm" onClick={medicos.refresh}>Tentar carregar médicos novamente</Button>
          )}>
            Não foi possível carregar a lista de médicos. {medicos.error.message}
          </Alert>
        )}
      </Card>

      <Card className="table-card">
        <DataTable caption={textos.titulo} columns={colunas} rows={lista.consultas} loading={lista.carregando}
          error={lista.erro} onRetry={lista.recarregar} emptyMessage={vazio} />
        {!lista.carregando && lista.consultas.length >= LIMITE_LISTAGEM && (
          <Alert tone="info">Mostrando as {LIMITE_LISTAGEM} primeiras consultas. Use os filtros para encontrar as demais.</Alert>
        )}
      </Card>
    </div>
  );
}
