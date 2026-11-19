import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import Alert from '../components/ui/Alert';
import Badge from '../components/ui/Badge';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';
import DataTable from '../components/ui/DataTable';
import Field from '../components/ui/Field';
import PageHeader from '../components/ui/PageHeader';
import { useAuth } from '../hooks/useAuth';
import { api } from '../services/api';
import { useRecords } from '../hooks/useRecords';
import { cancelarInscricaoFila, inscreverFilaEspera, listarFilaEspera } from '../services/filaEsperaService';

const ROTULOS = {
  AGUARDANDO: ['Aguardando', 'warning'],
  SINALIZADA: ['Vaga oferecida', 'info'],
  ATENDIDA: ['Atendido', 'success'],
  CANCELADA: ['Cancelada', 'danger'],
  EXPIRADA: ['Expirada', 'neutral'],
};
const TURNOS = { MANHA: 'Manhã', TARDE: 'Tarde', QUALQUER: 'Qualquer' };
const ID = (item) => item.id ?? item._id;
const ID_VALIDO = /^[a-f\d]{24}$/i;
const FORM_INICIAL = { pacienteId: '', especialidadeId: '', medicoId: '', turnoPreferido: 'QUALQUER', dataInicio: '', dataFim: '' };

function Icon({ name, size = 19 }) {
  const shapes = {
    list: <><path d="M4 7l2 2 4-4" /><path d="M13 7h7" /><path d="M4 15l2 2 4-4" /><path d="M13 15h7" /></>,
    trash: <><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" /></>,
    plus: <path d="M12 5v14M5 12h14" />,
    bell: <><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" /><path d="M10 21h4" /></>,
    x: <path d="M6 6l12 12M18 6 6 18" />,
  };
  return <svg aria-hidden="true" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{shapes[name]}</svg>;
}

function Indicador({ titulo, total, variante }) {
  return (
    <Card className="fila-espera__indicador">
      <div className="fila-espera__indicador-corpo">
        <div><span className="fila-espera__indicador-titulo">{titulo}</span><strong className="fila-espera__indicador-total">{total}</strong></div>
        <span className={`fila-espera__indicador-icone fila-espera__indicador-icone--${variante}`}><Icon name="list" size={24} /></span>
      </div>
    </Card>
  );
}

function dataBR(iso) {
  if (!iso) return '—';
  const valor = /^\d{4}-\d{2}-\d{2}$/.test(iso) ? `${iso}T12:00:00` : iso;
  const data = new Date(valor);
  return Number.isNaN(data.getTime()) ? '—' : new Intl.DateTimeFormat('pt-BR', { timeZone: 'America/Sao_Paulo', day: '2-digit', month: '2-digit', year: 'numeric' }).format(data);
}

function dataHojeSP() {
  const partes = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date());
  const pegar = (tipo) => partes.find((p) => p.type === tipo)?.value;
  return `${pegar('year')}-${pegar('month')}-${pegar('day')}`;
}

// Evita buscar /pacientes para o perfil PACIENTE, que não tem acesso ao cadastro geral.
function usePacientesSecretaria(ativo) {
  const [estado, setEstado] = useState({ records: [], loading: ativo, error: null });
  useEffect(() => {
    if (!ativo) return undefined;
    const ctrl = new AbortController();
    api.get('/pacientes', { signal: ctrl.signal })
      .then((dados) => {
        const itens = Array.isArray(dados) ? dados : dados?.items;
        if (!Array.isArray(itens)) throw new Error('A API retornou pacientes em formato inválido.');
        setEstado({ records: itens, loading: false, error: null });
      })
      .catch((error) => { if (!ctrl.signal.aborted) setEstado({ records: [], loading: false, error }); });
    return () => ctrl.abort();
  }, [ativo]);
  return estado;
}

export default function FilaEsperaPage() {
  const { usuario } = useAuth();
  const navigate = useNavigate();
  const perfil = usuario?.perfil;
  const secretaria = perfil === 'SECRETARIA_ADMIN';
  const permitido = secretaria || perfil === 'PACIENTE';
  const pacientes = usePacientesSecretaria(secretaria);
  const especialidades = useRecords('especialidades');
  const medicos = useRecords('medicos');
  const modalRef = useRef(null);
  const [form, setForm] = useState(FORM_INICIAL);
  const [enviando, setEnviando] = useState(false);
  const [erroForm, setErroForm] = useState('');
  const [aviso, setAviso] = useState('');
  const [filtroStatus, setFiltroStatus] = useState('ATIVAS');
  const [revisao, setRevisao] = useState(0);
  const [estado, setEstado] = useState({ revisao: -1, ativos: [], atendidos: [], erro: null });
  const [operandoId, setOperandoId] = useState(null);
  const sinalizacoesAnteriores = useRef(new Map());
  const [avisosDeVaga, setAvisosDeVaga] = useState([]);

  const recarregar = useCallback(() => setRevisao((valor) => valor + 1), []);
  useEffect(() => {
    if (!permitido) return undefined;
    const ctrl = new AbortController();
    Promise.all([
      listarFilaEspera({}, { signal: ctrl.signal }),
      listarFilaEspera({ status: 'ATENDIDA' }, { signal: ctrl.signal }),
    ]).then(([ativos, atendidos]) => {
      if (!Array.isArray(ativos) || !Array.isArray(atendidos)) throw new Error('A API retornou uma lista inválida.');
      // Apenas aviso visual: o servidor permanece a fonte de verdade do status.
      // Não marca inscrições como EXPIRADA: conforme a API, vaga ocupada por
      // terceiro volta a AGUARDANDO; EXPIRADA significa inscrição vencida.
      const anterior = sinalizacoesAnteriores.current;
      const atual = new Map();
      for (const item of ativos) {
        if (item.status === 'SINALIZADA' && item.ultimaSinalizacao?.dataHora) {
          atual.set(item.id, { dataHora: item.ultimaSinalizacao.dataHora, pacienteNome: item.pacienteNome });
        }
      }
      const perdidas = [];
      for (const item of ativos) {
        const previa = anterior.get(item.id);
        if (previa && item.status === 'AGUARDANDO') {
          perdidas.push({ id: item.id, texto: `A vaga anteriormente sinalizada para ${item.pacienteNome || 'o paciente'} não está mais disponível. A inscrição continua aguardando outra oportunidade.` });
        }
      }
      sinalizacoesAnteriores.current = atual;
      if (perdidas.length) setAvisosDeVaga((prev) => [...perdidas, ...prev].slice(0, 5));
      setEstado({ revisao, ativos, atendidos, erro: null });
    }).catch((erro) => {
      if (!ctrl.signal.aborted) setEstado({ revisao, ativos: [], atendidos: [], erro });
    });
    return () => ctrl.abort();
  }, [permitido, revisao]);

  const carregando = estado.revisao !== revisao;
  const lista = filtroStatus === 'ATENDIDA' ? estado.atendidos : estado.ativos;
  const contagens = useMemo(() => ({
    aguardando: estado.ativos.filter((item) => item.status === 'AGUARDANDO').length,
    sinalizada: estado.ativos.filter((item) => item.status === 'SINALIZADA').length,
    atendidos: estado.atendidos.length,
  }), [estado.ativos, estado.atendidos]);

  const medicosElegiveis = useMemo(() => medicos.records.filter((m) =>
    m.ativo !== false && (m.especialidadeIds ?? []).includes(form.especialidadeId)
  ), [medicos.records, form.especialidadeId]);

  function alterar(campo, valor) {
    setForm((anterior) => ({ ...anterior, [campo]: valor, ...(campo === 'especialidadeId' ? { medicoId: '' } : {}) }));
  }
  function abrirFormulario() {
    setForm(FORM_INICIAL);
    setErroForm('');
    modalRef.current?.showModal();
  }
  function fecharFormulario() {
    if (!enviando) modalRef.current?.close();
  }

  async function cadastrar(event) {
    event.preventDefault();
    setErroForm('');
    if (secretaria && !ID_VALIDO.test(form.pacienteId)) { setErroForm('Selecione um paciente válido.'); return; }
    if (!ID_VALIDO.test(form.especialidadeId)) { setErroForm('Selecione uma especialidade válida.'); return; }
    const hoje = dataHojeSP();
    if (form.dataInicio && form.dataInicio < hoje) { setErroForm('A data inicial não pode estar no passado.'); return; }
    if (form.dataFim && form.dataFim < (form.dataInicio || hoje)) { setErroForm('A data final deve ser igual ou posterior à inicial.'); return; }
    setEnviando(true);
    try {
      await inscreverFilaEspera({
        ...(secretaria ? { pacienteId: form.pacienteId } : {}),
        especialidadeId: form.especialidadeId,
        medicoId: form.medicoId || null,
        turnoPreferido: form.turnoPreferido,
        dataInicio: form.dataInicio || null,
        dataFim: form.dataFim || null,
      });
      modalRef.current?.close();
      setAviso('Inscrição realizada com sucesso.');
      setFiltroStatus('ATIVAS');
      recarregar();
    } catch (erro) {
      setErroForm(erro.message || 'Não foi possível inscrever o paciente.');
    } finally {
      setEnviando(false);
    }
  }

  async function cancelar(item) {
    const nome = item.pacienteNome || 'este paciente';
    if (!window.confirm(`Cancelar a inscrição de ${nome} na fila de espera? Esta operação será enviada ao servidor.`)) return;
    setOperandoId(item.id);
    setAviso('');
    try {
      await cancelarInscricaoFila(item.id);
      setAviso('Inscrição cancelada com sucesso.');
      recarregar();
    } catch (erro) {
      setAviso(`Não foi possível cancelar a inscrição: ${erro.message}`);
    } finally {
      setOperandoId(null);
    }
  }

  function agendarSinalizacao(item) {
    const sinal = item.ultimaSinalizacao;
    if (perfil === 'PACIENTE') {
      navigate('/avisos-vaga', { state: { avisoId: sinal?.notificacaoId } });
      return;
    }
    if (item.status !== 'SINALIZADA' || !sinal?.dataHora || !sinal?.medicoId || !item.especialidadeId) {
      setAviso('A sinalização não contém todos os dados para iniciar o agendamento. Atualize a fila.');
      return;
    }
    // A rota de agendamento efetua a verificação de disponibilidade antes de confirmar.
    navigate('/nova-consulta', { state: {
      origemFila: {
        inscricaoId: item.id,
        pacienteId: item.pacienteId,
        pacienteNome: item.pacienteNome,
        especialidadeId: item.especialidadeId,
        medicoId: sinal.medicoId,
        inicio: sinal.dataHora,
      },
    } });
  }

  // Reconsulta periódica enquanto a página estiver aberta; não abre conexão persistente.
  useEffect(() => {
    if (!permitido) return undefined;
    const timer = setInterval(() => recarregar(), 30000);
    return () => clearInterval(timer);
  }, [permitido, recarregar]);

  const colunas = [
    { key: 'pacienteNome', label: 'Paciente', render: (p) => p.pacienteNome || '—' },
    { key: 'especialidadeNome', label: 'Especialidade', render: (p) => p.especialidadeNome || '—' },
    { key: 'medicoNome', label: 'Médico', render: (p) => p.medicoNome || 'Qualquer' },
    { key: 'turnoPreferido', label: 'Preferências', render: (p) => <span title={`Período: ${p.dataInicio || 'livre'} até ${p.dataFim || 'validade'}`}>{TURNOS[p.turnoPreferido] || 'Qualquer'} · {p.dataInicio || 'Sem início definido'}</span> },
    { key: 'criadoEm', label: 'Entrada', render: (p) => dataBR(p.criadoEm) },
    { key: 'status', label: 'Status', render: (p) => <Badge tone={ROTULOS[p.status]?.[1] ?? 'neutral'}>{ROTULOS[p.status]?.[0] ?? p.status}</Badge> },
    { key: 'acoes', label: 'Ações', align: 'right', render: (p) => (
      <div className="fila-espera__acoes">
        {p.status === 'SINALIZADA' && p.ultimaSinalizacao && (
          <span className="fila-espera__sinal" title={`Vaga: ${dataBR(p.ultimaSinalizacao.dataHora)} — ${p.ultimaSinalizacao.medicoNome || 'médico'}`}>
            <Icon name="bell" /> <span className="sr-only">Vaga sinalizada</span>
          </span>
        )}
        {p.status === 'SINALIZADA' && p.ultimaSinalizacao && (
          <Button size="sm" onClick={() => agendarSinalizacao(p)} disabled={!!operandoId}>
            Agendar vaga
          </Button>
        )}
        {(p.status === 'AGUARDANDO' || p.status === 'SINALIZADA') && (
          <button className="fila-espera__acao fila-espera__acao--remover" type="button" disabled={!!operandoId}
            onClick={() => cancelar(p)} title="Cancelar inscrição" aria-label={`Cancelar inscrição de ${p.pacienteNome || 'paciente'}`}>
            <Icon name="trash" />
          </button>
        )}
        {p.status === 'ATENDIDA' && <span className="fila-espera__sem-acao">—</span>}
      </div>
    ) },
  ];

  if (!permitido) return <div className="page"><Alert>Este módulo é destinado à secretaria e aos pacientes.</Alert></div>;
  return (
    <div className="page fila-espera">
      <PageHeader title="Fila de espera"
        description="Quando um horário é liberado, o sistema sinaliza as inscrições compatíveis com a vaga."
        action={<Button onClick={abrirFormulario}><Icon name="plus" /> Entrar na fila</Button>} />

      <section className="fila-espera__indicadores" aria-label="Resumo da fila de espera">
        <Indicador titulo="Aguardando" total={carregando ? '…' : contagens.aguardando} variante="teal" />
        <Indicador titulo="Vaga oferecida" total={carregando ? '…' : contagens.sinalizada} variante="azul" />
        <Indicador titulo="Atendidos" total={carregando ? '…' : contagens.atendidos} variante="verde" />
      </section>

      {aviso && <Alert tone={aviso.startsWith('Não foi possível') ? 'danger' : 'info'}>{aviso}</Alert>}
      {avisosDeVaga.map((item) => (
        <div className="fila-v4__aviso" key={`${item.id}-${item.texto}`} role="status">
          <Alert tone="warning">{item.texto}</Alert>
          <Button variant="ghost" size="sm" onClick={() => setAvisosDeVaga((prev) => prev.filter((a) => a !== item))}>Dispensar aviso</Button>
        </div>
      ))}
      <div className="fila-espera__controles">
        <Field label="Visualizar inscrições" as="select" value={filtroStatus} onChange={(e) => setFiltroStatus(e.target.value)}>
          <option value="ATIVAS">Ativas (aguardando e sinalizadas)</option>
          <option value="ATENDIDA">Atendidas</option>
        </Field>
        <Button variant="outline" onClick={recarregar} disabled={carregando}>Atualizar lista</Button>
      </div>
      <Card className="table-card fila-espera__tabela">
        <DataTable caption="Inscrições na fila de espera" columns={colunas} rows={carregando ? [] : lista}
          loading={carregando} error={estado.erro} onRetry={recarregar}
          emptyMessage="Nenhuma inscrição encontrada." />
      </Card>
      <p className="fila-espera__nota">Os dados são carregados da API. Unidade, prioridade e dias da semana não aparecem porque não fazem parte do contrato atual do backend. A sinalização de vaga ocorre automaticamente no servidor.</p>

      <dialog ref={modalRef} className="modal fila-espera__modal" aria-labelledby="fila-espera-modal-titulo" onCancel={(e) => { if (enviando) e.preventDefault(); }}>
        <div className="modal__header">
          <h2 id="fila-espera-modal-titulo">Entrar na fila</h2>
          <Button className="modal__close" variant="ghost" size="icon" aria-label="Fechar formulário" onClick={fecharFormulario} disabled={enviando}><Icon name="x" /></Button>
        </div>
        <form onSubmit={cadastrar}>
          <div className="form-grid">
            {secretaria && <Field as="select" className="form-grid__wide" label="Paciente" required value={form.pacienteId} disabled={pacientes.loading || enviando} onChange={(e) => alterar('pacienteId', e.target.value)}>
              <option value="">Selecione o paciente</option>
              {pacientes.records.map((p) => <option key={ID(p)} value={ID(p)}>{p.nome || p.nomeCompleto || ID(p)}</option>)}
            </Field>}
            <Field as="select" className="form-grid__wide" label="Especialidade" required value={form.especialidadeId} disabled={especialidades.loading || enviando} onChange={(e) => alterar('especialidadeId', e.target.value)}>
              <option value="">Selecione a especialidade</option>
              {especialidades.records.filter((e) => e.ativa !== false).map((e) => <option key={ID(e)} value={ID(e)}>{e.nome || ID(e)}</option>)}
            </Field>
            <Field as="select" label="Médico (opcional)" value={form.medicoId} disabled={!form.especialidadeId || medicos.loading || enviando} onChange={(e) => alterar('medicoId', e.target.value)}>
              <option value="">Qualquer médico</option>
              {medicosElegiveis.map((m) => <option key={ID(m)} value={ID(m)}>{m.nome || ID(m)}</option>)}
            </Field>
            <Field as="select" label="Turno preferido" value={form.turnoPreferido} disabled={enviando} onChange={(e) => alterar('turnoPreferido', e.target.value)}>
              <option value="QUALQUER">Qualquer</option><option value="MANHA">Manhã</option><option value="TARDE">Tarde</option>
            </Field>
            <Field label="Data inicial (opcional)" type="date" min={dataHojeSP()} value={form.dataInicio} disabled={enviando} onChange={(e) => alterar('dataInicio', e.target.value)} />
            <Field label="Data final (opcional)" type="date" min={form.dataInicio || dataHojeSP()} value={form.dataFim} disabled={enviando} onChange={(e) => alterar('dataFim', e.target.value)} />
          </div>
          {(pacientes.error && secretaria || especialidades.error || medicos.error) && <Alert>Não foi possível carregar um dos cadastros. Feche e abra novamente o formulário ou atualize a página.</Alert>}
          {erroForm && <Alert>{erroForm}</Alert>}
          <div className="form-actions">
            <Button variant="outline" onClick={fecharFormulario} disabled={enviando}>Cancelar</Button>
            <Button type="submit" loading={enviando} disabled={especialidades.loading || !!especialidades.error || !!medicos.error || (secretaria && (pacientes.loading || !!pacientes.error))}>Confirmar inscrição</Button>
          </div>
        </form>
      </dialog>
    </div>
  );
}
