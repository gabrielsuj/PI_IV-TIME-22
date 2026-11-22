import { useEffect, useState } from 'react';
import { Link, useLocation } from 'react-router';
import Alert from '../components/ui/Alert';
import Badge from '../components/ui/Badge';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';
import DataTable from '../components/ui/DataTable';
import Modal from '../components/ui/Modal';
import PageHeader from '../components/ui/PageHeader';
import { agendarPeloAviso, listarAvisosVaga } from '../services/avisosVagaService';
import { buscaDeOutraVaga, explicarExpiracao } from '../utils/avisoVaga';
import { dataHoraDaConsulta, FUSO_CLINICA } from '../utils/consulta';

/**
 * @author Murilo Moraes (modificador, #58: motivo da expiração e volta à busca de vagas)
 */

const INTERVALO_ATUALIZACAO = 15_000;
const validade = new Intl.DateTimeFormat('pt-BR', {
  timeZone: FUSO_CLINICA, dateStyle: 'short', timeStyle: 'short',
});
const STATUS = {
  DISPONIVEL: { texto: 'Disponível', tom: 'info' },
  EXPIRADO: { texto: 'Expirado', tom: 'neutral' },
  AGENDADO: { texto: 'Agendado', tom: 'success' },
};

/** Saída oferecida ao paciente quando o aviso não vale mais (#58). */
function SaidaDoAviso({ aviso, size = 'sm' }) {
  if (explicarExpiracao(aviso?.motivo).buscarOutra) {
    const busca = buscaDeOutraVaga(aviso);
    return <Button to={busca.to} state={busca.state} variant="outline" size={size}>Buscar outra vaga</Button>;
  }
  return <Button to="/fila-espera" variant="outline" size={size}>Ver fila de espera</Button>;
}

export default function AvisosVagaPage() {
  const [lista, setLista] = useState({ avisos: [], carregando: true, erro: null });
  const [revisao, setRevisao] = useState(0);
  const { state } = useLocation();
  const [selecionado, setSelecionado] = useState(state?.avisoId ?? null);
  const [enviando, setEnviando] = useState(false);
  const [resultado, setResultado] = useState(null);
  const [erroConfirmacao, setErroConfirmacao] = useState('');
  function atualizar() {
    setLista((anterior) => ({ ...anterior, carregando: true, erro: null }));
    setRevisao((r) => r + 1);
  }

  useEffect(() => {
    let controller;
    function carregar() {
      controller?.abort();
      const requisicao = new AbortController();
      controller = requisicao;
      listarAvisosVaga({ signal: requisicao.signal })
        .then((avisos) => {
          if (!requisicao.signal.aborted) setLista({ avisos, carregando: false, erro: null });
        })
        .catch((erro) => {
          if (!requisicao.signal.aborted) setLista((anterior) => ({ ...anterior, carregando: false, erro }));
        });
    }
    carregar();
    const intervalo = window.setInterval(carregar, INTERVALO_ATUALIZACAO);
    window.addEventListener('focus', carregar);
    return () => {
      window.clearInterval(intervalo);
      window.removeEventListener('focus', carregar);
      controller?.abort();
    };
  }, [revisao]);

  const avisoAtual = lista.avisos.find((aviso) => aviso.id === selecionado);
  const disponibilidadeVerificada = !lista.carregando && !lista.erro;
  const podeConfirmar = disponibilidadeVerificada && avisoAtual?.status === 'DISPONIVEL';

  function fecharConfirmacao() {
    setSelecionado(null);
    setErroConfirmacao('');
  }

  async function confirmar(evento) {
    evento.preventDefault();
    if (enviando || !podeConfirmar) return;
    setEnviando(true);
    setResultado(null);
    setErroConfirmacao('');
    try {
      const consulta = await agendarPeloAviso(avisoAtual.id);
      setResultado({ consultaId: consulta.id });
      fecharConfirmacao();
    } catch (erro) {
      if (erro.status === 404 || erro.status === 409) {
        // Guarda o aviso para explicar o motivo depois que a lista for atualizada.
        setResultado({ erro: erro.message, avisoId: avisoAtual.id, aviso: avisoAtual });
        fecharConfirmacao();
      } else {
        setErroConfirmacao(erro.message);
      }
    } finally {
      setEnviando(false);
      atualizar();
    }
  }

  const colunas = [
    { key: 'medicoNome', label: 'Médico' },
    { key: 'horario', label: 'Consulta', render: (aviso) => {
      const { data, periodo } = dataHoraDaConsulta(aviso);
      return `${data} · ${periodo}`;
    } },
    { key: 'validade', label: 'Válido até', render: (aviso) => validade.format(new Date(aviso.validoAte)) },
    { key: 'status', label: 'Status', render: (aviso) => (
      <div className="aviso-vaga__status">
        <Badge tone={STATUS[aviso.status].tom}>{STATUS[aviso.status].texto}</Badge>
        {aviso.status === 'EXPIRADO' && (
          <span className="aviso-vaga__motivo">{explicarExpiracao(aviso.motivo).rotulo}</span>
        )}
      </div>
    ) },
    { key: 'acoes', label: 'Ações', align: 'right', render: (aviso) => {
      if (aviso.status === 'AGENDADO' && aviso.consultaAgendadaId) {
        return <Button to={`/consultas/${aviso.consultaAgendadaId}`} variant="outline" size="sm">Ver consulta</Button>;
      }
      if (aviso.status === 'DISPONIVEL') {
        return <Button size="sm" onClick={() => setSelecionado(aviso.id)}>Agendar vaga</Button>;
      }
      return <SaidaDoAviso aviso={aviso} />;
    } },
  ];

  const horario = avisoAtual && dataHoraDaConsulta(avisoAtual);
  // Depois de um 409, a lista recarregada diz por que a vaga se perdeu.
  const avisoDoErro = resultado?.avisoId
    ? lista.avisos.find((aviso) => aviso.id === resultado.avisoId) ?? resultado.aviso
    : null;
  const textoDoErro = avisoDoErro?.status === 'EXPIRADO' && avisoDoErro.motivo
    ? explicarExpiracao(avisoDoErro.motivo).texto
    : resultado?.erro;

  return (
    <div className="page">
      <PageHeader title="Avisos de vaga" description="Acompanhe as vagas liberadas para você pela fila de espera."
        action={<Button variant="outline" onClick={atualizar}>Atualizar avisos</Button>} />
      {resultado?.consultaId && (
        <Alert tone="success" action={<Button to={`/consultas/${resultado.consultaId}`} variant="outline">Ver consulta agendada</Button>}>
          Consulta agendada com sucesso.
        </Alert>
      )}
      {resultado?.erro && (
        <Alert action={avisoDoErro?.status === 'AGENDADO' ? undefined : <SaidaDoAviso aviso={avisoDoErro} size="md" />}>
          {textoDoErro}
        </Alert>
      )}
      <Card>
        <DataTable caption="Avisos de vaga" columns={colunas} rows={lista.avisos} loading={lista.carregando}
          error={lista.erro} onRetry={atualizar} emptyMessage={(
            <>
              Você ainda não recebeu avisos de vaga. Entre na <Link to="/fila-espera">fila de espera</Link> para
              ser avisado quando um horário compatível for liberado.
            </>
          )} />
      </Card>
      {avisoAtual && (
        <Modal title="Confirmar agendamento" description={`${avisoAtual.medicoNome} · ${horario.data}, ${horario.periodo}`}
          busy={enviando} onClose={fecharConfirmacao}>
          <form onSubmit={confirmar}>
            {lista.carregando && <p role="status">Verificando a disponibilidade da vaga…</p>}
            {lista.erro && (
              <Alert action={<Button variant="outline" size="sm" onClick={atualizar}>Tentar novamente</Button>}>
                Não foi possível verificar a disponibilidade da vaga. {lista.erro.message}
              </Alert>
            )}
            {erroConfirmacao && (!disponibilidadeVerificada || podeConfirmar) && <Alert>{erroConfirmacao}</Alert>}
            {disponibilidadeVerificada && avisoAtual.status === 'EXPIRADO' && (
              <Alert action={<SaidaDoAviso aviso={avisoAtual} />}>
                {explicarExpiracao(avisoAtual.motivo).texto}
              </Alert>
            )}
            {disponibilidadeVerificada && avisoAtual.status === 'AGENDADO' && (
              <Alert tone="success" action={avisoAtual.consultaAgendadaId
                ? <Button to={`/consultas/${avisoAtual.consultaAgendadaId}`} variant="outline" size="sm">Ver consulta</Button>
                : undefined}>
                A consulta deste aviso já foi agendada.
              </Alert>
            )}
            <div className="form-actions">
              <Button variant="outline" disabled={enviando} onClick={fecharConfirmacao}>Voltar</Button>
              <Button type="submit" loading={enviando} disabled={!podeConfirmar}>Confirmar agendamento</Button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
