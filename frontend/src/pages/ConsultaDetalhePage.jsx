import { useEffect, useRef, useState } from 'react';
import { useLocation, useParams } from 'react-router';
import HistoricoConsulta from '../components/consultas/HistoricoConsulta';
import Alert from '../components/ui/Alert';
import Badge from '../components/ui/Badge';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';
import Field from '../components/ui/Field';
import Modal from '../components/ui/Modal';
import PageHeader from '../components/ui/PageHeader';
import { useAuth } from '../hooks/useAuth';
import { usePrazoConsulta } from '../hooks/usePrazoConsulta';
import { alterarStatusConsulta, buscarConsulta, cancelarConsulta } from '../services/consultasService';
import { acoesStatusDaConsulta, dataHoraDaConsulta, STATUS_ATIVOS, STATUS_CONSULTA } from '../utils/consulta';
import { retornoPainel } from '../utils/painel';

const LIMITE_MOTIVO = 255;
const ACOES_STATUS = {
  CONFIRMADA: { rotulo: 'Confirmar consulta', enviando: 'Confirmando…', sucesso: 'Consulta confirmada.' },
  CONCLUIDA: { rotulo: 'Concluir consulta', enviando: 'Concluindo…', sucesso: 'Consulta concluída.' },
  FALTA: { rotulo: 'Registrar falta', enviando: 'Registrando falta…', sucesso: 'Falta registrada.' },
};

/** Detalhe recarregável depois de cancelar ou alterar status. */
function useConsulta(id) {
  const [revisao, setRevisao] = useState(0);
  const chave = `${id}|${revisao}`;
  const [estado, setEstado] = useState({ chave: null, consulta: null, erro: null });

  useEffect(() => {
    const controller = new AbortController();
    buscarConsulta(id, { signal: controller.signal })
      .then((consulta) => { if (!controller.signal.aborted) setEstado({ chave, consulta, erro: null }); })
      .catch((erro) => {
        if (!controller.signal.aborted) setEstado((anterior) => ({ chave, consulta: anterior.consulta, erro }));
      });
    return () => controller.abort();
  }, [id, chave]);

  // Ao recarregar, mantém a consulta anterior na tela até a nova chegar (sem "piscar").
  return { ...estado, revisao, carregando: estado.chave !== chave, recarregar: () => setRevisao((r) => r + 1) };
}

function mensagemDeErro(erro) {
  if (erro.status === 404) return 'Consulta não encontrada. Ela pode ter sido removida ou o endereço está incorreto.';
  if (erro.status === 403) return 'Você não tem acesso a esta consulta.';
  if (erro.status === 400) return 'Endereço de consulta inválido.';
  return `Não foi possível carregar a consulta. ${erro.message}`;
}

function CancelarConsultaModal({ consulta, onFechar, onCancelada, onRecusada }) {
  const [motivo, setMotivo] = useState('');
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState(null);
  const { data, periodo } = dataHoraDaConsulta(consulta);

  async function confirmar(event) {
    event.preventDefault();
    setEnviando(true);
    setErro(null);
    try {
      await cancelarConsulta(consulta.id, motivo);
      onCancelada();
    } catch (falha) {
      setEnviando(false);
      // 409: a consulta mudou (já cancelada, já começou…). Não adianta tentar de novo: fecha e recarrega.
      if (falha.status === 409) onRecusada(falha.message);
      else setErro(falha);
    }
  }

  return (
    <Modal title="Cancelar consulta?" description={`${consulta.especialidadeNome ?? 'Consulta'} com ${consulta.medicoNome ?? 'o médico'} em ${data}, ${periodo}.`}
      busy={enviando} onClose={onFechar}>
      <form onSubmit={confirmar}>
        <p>O horário volta a ficar disponível para outros pacientes. Esta ação não pode ser desfeita.</p>
        <Field as="textarea" label="Motivo (opcional)" value={motivo} maxLength={LIMITE_MOTIVO} disabled={enviando}
          hint={`Não informe dados de saúde. ${motivo.length}/${LIMITE_MOTIVO} caracteres.`}
          onChange={(event) => setMotivo(event.target.value)} />
        {erro && <Alert>{erro.message || 'Não foi possível cancelar a consulta. Tente novamente.'}</Alert>}
        <footer className="form-actions">
          <Button variant="outline" onClick={onFechar} disabled={enviando}>Voltar</Button>
          <Button type="submit" variant="danger" loading={enviando}>{enviando ? 'Cancelando…' : 'Confirmar cancelamento'}</Button>
        </footer>
      </form>
    </Modal>
  );
}

export default function ConsultaDetalhePage() {
  const { id } = useParams();
  return <DetalheConsulta key={id} id={id} />;
}

function DetalheConsulta({ id }) {
  const { usuario } = useAuth();
  const location = useLocation();
  const retorno = retornoPainel(location, usuario);
  const { consulta, erro, carregando, recarregar, chave, revisao } = useConsulta(id);
  const [cancelando, setCancelando] = useState(false);
  const [aviso, setAviso] = useState(null);
  const [enviandoStatus, setEnviandoStatus] = useState(null);
  const [leituraPendente, setLeituraPendente] = useState(null);
  const emEnvio = useRef(false);
  const montado = useRef(false);
  const avisoRef = useRef(null);
  const agora = usePrazoConsulta(consulta?.fim);
  const acoes = consulta ? acoesStatusDaConsulta(consulta, usuario?.perfil, agora) : [];
  const conferir = leituraPendente !== null && (carregando || Boolean(erro) || leituraPendente === chave);
  const acessoRecusado = erro && [400, 403, 404].includes(erro.status);
  const bloqueado = carregando || Boolean(erro) || Boolean(enviandoStatus) || conferir;

  useEffect(() => {
    montado.current = true;
    return () => { montado.current = false; };
  }, []);

  useEffect(() => { if (aviso) avisoRef.current?.focus(); }, [aviso]);

  function sinalizarConferencia(texto) {
    setLeituraPendente(chave);
    setAviso({ tom: 'danger', texto, conferir: true });
  }

  async function alterarStatus(destino) {
    if (emEnvio.current || bloqueado || cancelando || !acoes.includes(destino)) return;
    emEnvio.current = true;
    setEnviandoStatus(destino);
    setAviso(null);
    setLeituraPendente(null);
    try {
      const resultado = await alterarStatusConsulta(consulta.id, destino);
      if (!montado.current) return;
      if (resultado?.id !== consulta.id || resultado?.status !== destino) {
        sinalizarConferencia('Não foi possível verificar o resultado da alteração. Confira o status atual antes de tentar novamente.');
        return;
      }
      setAviso({ tom: 'success', texto: ACOES_STATUS[destino].sucesso });
      recarregar();
    } catch (falha) {
      if (!montado.current) return;
      if (!Number.isInteger(falha?.status) || falha.status === 0 || (falha.status >= 500 && falha.status !== 503)) {
        sinalizarConferencia('Não foi possível verificar o resultado da alteração. Confira o status atual antes de tentar novamente.');
      } else if ([403, 404].includes(falha.status)) {
        sinalizarConferencia(falha.message || 'Não foi possível alterar esta consulta. Atualize os dados para conferir seu acesso.');
      } else {
        setAviso({ tom: 'danger', texto: falha.message || 'Não foi possível alterar o status da consulta.' });
        if (falha.status === 409) recarregar();
      }
    } finally {
      emEnvio.current = false;
      if (montado.current) setEnviandoStatus(null);
    }
  }

  const voltar = <Button to={retorno || '/consultas'} variant="outline" disabled={Boolean(enviandoStatus)}>
    {retorno ? '‹ Voltar ao painel' : '‹ Voltar para consultas'}
  </Button>;
  const feedback = aviso && <Alert ref={avisoRef} tabIndex={-1} tone={aviso.conferir && !conferir ? 'info' : aviso.tom}
    action={conferir && !acessoRecusado && <Button variant="outline" size="sm" disabled={carregando} onClick={recarregar}>Conferir status da consulta</Button>}>
    {aviso.conferir && !conferir ? 'Status atualizado. Confira o resumo antes de decidir sobre outra alteração.' : aviso.texto}
  </Alert>;

  if (carregando && !consulta) {
    return (
      <div className="page">
        <PageHeader title="Detalhes da consulta" action={voltar} />
        <Card><p className="empty-state" role="status">Carregando consulta…</p></Card>
      </div>
    );
  }

  if (erro && (!consulta || acessoRecusado)) {
    const recuperavel = ![400, 403, 404].includes(erro.status);
    return (
      <div className="page">
        <PageHeader title="Detalhes da consulta" action={voltar} />
        {feedback}
        <Alert action={recuperavel && <Button variant="outline" size="sm" onClick={recarregar}>Tentar novamente</Button>}>
          {mensagemDeErro(erro)}
        </Alert>
      </div>
    );
  }

  const { data, periodo } = dataHoraDaConsulta(consulta);
  const status = STATUS_CONSULTA[consulta.status];
  const paciente = usuario?.perfil === 'PACIENTE';
  const jaComecou = STATUS_ATIVOS.includes(consulta.status) && !consulta.podeCancelar;

  return (
    <div className="page">
      <PageHeader title="Detalhes da consulta" description={`${data}, ${periodo}`} action={voltar} />

      {feedback}
      {erro && <Alert action={<Button variant="outline" size="sm" onClick={recarregar}>Tentar novamente</Button>}>
        Não foi possível atualizar a consulta. O resumo abaixo pode estar desatualizado. {erro.message}
      </Alert>}
      {carregando && <p role="status">Atualizando consulta…</p>}

      <Card title="Resumo da reserva" className="consulta-resumo" aria-busy={carregando || Boolean(enviandoStatus) || undefined}
        action={<Badge tone={status?.tom}>{status?.rotulo ?? consulta.status}</Badge>}>
        <dl className="resumo-agendamento">
          <div><dt>Data</dt><dd>{data}</dd></div>
          <div><dt>Horário</dt><dd>{periodo}</dd></div>
          <div><dt>Paciente</dt><dd>{consulta.pacienteNome ?? '—'}{paciente ? ' (você)' : ''}</dd></div>
          <div><dt>Médico</dt><dd>{consulta.medicoNome ?? '—'}</dd></div>
          <div><dt>Especialidade</dt><dd>{consulta.especialidadeNome ?? '—'}</dd></div>
          {consulta.status === 'CANCELADA' && (
            <div><dt>Motivo do cancelamento</dt><dd>{consulta.motivoCancelamento || 'Não informado'}</dd></div>
          )}
        </dl>

        {jaComecou && (
          <Alert tone="info">Esta consulta já começou e não pode mais ser cancelada.</Alert>
        )}

        {['MEDICO', 'SECRETARIA_ADMIN'].includes(usuario?.perfil) && STATUS_ATIVOS.includes(consulta.status)
          && Date.parse(consulta.fim) > agora && <p>Conclusão e falta ficam disponíveis após o fim previsto da consulta.</p>}
        {(acoes.length > 0 || consulta.podeCancelar) && (
          <div className="form-actions">
            {acoes.map((destino) => <Button key={destino} variant={destino === 'FALTA' ? 'outline' : 'primary'}
              disabled={bloqueado || cancelando} loading={enviandoStatus === destino} onClick={() => alterarStatus(destino)}>
              {enviandoStatus === destino ? ACOES_STATUS[destino].enviando : ACOES_STATUS[destino].rotulo}
            </Button>)}
            {consulta.podeCancelar && <Button variant="danger" disabled={bloqueado || cancelando}
              onClick={() => { if (!emEnvio.current) { setAviso(null); setCancelando(true); } }}>Cancelar consulta</Button>}
          </div>
        )}
      </Card>

      <HistoricoConsulta consultaId={consulta.id} revisao={revisao} />

      {cancelando && (
        <CancelarConsultaModal
          consulta={consulta}
          onFechar={() => setCancelando(false)}
          onCancelada={() => {
            setCancelando(false);
            setAviso({ tom: 'success', texto: 'Consulta cancelada. O horário foi liberado para outros pacientes.' });
            recarregar();
          }}
          onRecusada={(mensagem) => {
            setCancelando(false);
            setAviso({ tom: 'danger', texto: `${mensagem} Os dados abaixo foram atualizados.` });
            recarregar();
          }}
        />
      )}
    </div>
  );
}
