import { useEffect, useState } from 'react';
import { useParams } from 'react-router';
import Alert from '../components/ui/Alert';
import Badge from '../components/ui/Badge';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';
import Field from '../components/ui/Field';
import Modal from '../components/ui/Modal';
import PageHeader from '../components/ui/PageHeader';
import { useAuth } from '../hooks/useAuth';
import { buscarConsulta, cancelarConsulta } from '../services/consultasService';
import { dataHoraDaConsulta, STATUS_ATIVOS, STATUS_CONSULTA } from '../utils/consulta';

const LIMITE_MOTIVO = 255;

/** Detalhe da consulta, recarregável depois de uma ação (cancelar, e futuramente as da #28). */
function useConsulta(id) {
  const [revisao, setRevisao] = useState(0);
  const chave = `${id}|${revisao}`;
  const [estado, setEstado] = useState({ chave: null, consulta: null, erro: null });

  useEffect(() => {
    const controller = new AbortController();
    buscarConsulta(id, { signal: controller.signal })
      .then((consulta) => setEstado({ chave, consulta, erro: null }))
      .catch((erro) => { if (!controller.signal.aborted) setEstado({ chave, consulta: null, erro }); });
    return () => controller.abort();
  }, [id, chave]);

  // Ao recarregar, mantém a consulta anterior na tela até a nova chegar (sem "piscar").
  return { ...estado, carregando: estado.chave !== chave, recarregar: () => setRevisao((r) => r + 1) };
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
  const { usuario } = useAuth();
  const { consulta, erro, carregando, recarregar } = useConsulta(id);
  const [cancelando, setCancelando] = useState(false);
  const [aviso, setAviso] = useState(null);

  const voltar = <Button to="/consultas" variant="outline">‹ Voltar para consultas</Button>;

  if (carregando && !consulta) {
    return (
      <div className="page">
        <PageHeader title="Detalhes da consulta" action={voltar} />
        <Card><p className="empty-state" role="status">Carregando consulta…</p></Card>
      </div>
    );
  }

  if (erro) {
    const recuperavel = ![400, 403, 404].includes(erro.status);
    return (
      <div className="page">
        <PageHeader title="Detalhes da consulta" action={voltar} />
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

      {aviso && <Alert tone={aviso.tom}>{aviso.texto}</Alert>}

      <Card title="Resumo da reserva" className="consulta-resumo" aria-busy={carregando || undefined}
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

        {/* #28 (Vinicius): botões de confirmar, concluir e registrar falta entram aqui, ao lado do cancelar,
            usando consulta.status e usuario.perfil. Depois da ação, chamar recarregar(). */}
        {consulta.podeCancelar && (
          <div className="form-actions">
            <Button variant="danger" onClick={() => { setAviso(null); setCancelando(true); }}>Cancelar consulta</Button>
          </div>
        )}
      </Card>

      {/* #46 (Vinicius): linha do tempo do histórico da consulta entra aqui, como um <Card title="Histórico">. */}

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
