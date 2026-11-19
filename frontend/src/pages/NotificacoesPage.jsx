import { useState } from 'react';
import { useRecords } from '../hooks/useRecords';
import { api } from '../services/api';
import Alert from '../components/ui/Alert';
import Badge from '../components/ui/Badge';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';
import DataTable from '../components/ui/DataTable';
import PageHeader from '../components/ui/PageHeader';

const dataHora = new Intl.DateTimeFormat('pt-BR', {
  timeZone: 'America/Sao_Paulo', dateStyle: 'short', timeStyle: 'short',
});

export default function NotificacoesPage() {
  const { records, loading, error, refresh } = useRecords('notificacoes');
  const [ocupada, setOcupada] = useState(false);
  const [aviso, setAviso] = useState(null);
  const naoLidas = records.filter(n => !n.lida).length;

  async function agir(acao, id) {
    setOcupada(true);
    setAviso(null);
    try {
      await api.post('/notificacoes/' + acao, { notificacaoId: id });
      setAviso({ tom: 'success', texto: acao === 'confirmar' ? 'Presença confirmada.' : 'Notificação marcada como lida.' });
    } catch (erro) {
      setAviso({ tom: 'danger', texto: erro.message });
    } finally {
      setOcupada(false);
      refresh();
    }
  }

  const colunas = [
    { key: 'titulo', label: 'Notificação', render: n => <><strong>{n.titulo}</strong><p>{n.mensagem}</p></> },
    { key: 'dataHora', label: 'Consulta', render: n => <>{n.medicoNome} · {dataHora.format(new Date(n.dataHora))}</> },
    { key: 'lida', label: 'Leitura', render: n => <Badge tone={n.lida ? 'neutral' : 'info'}>{n.lida ? 'Lida' : 'Não lida'}</Badge> },
    { key: 'acoes', label: 'Ações', render: n => <>
      {n.presencaConfirmada && <Badge tone="success">Presença confirmada</Badge>}
      {n.podeConfirmar && <Button disabled={ocupada || loading} onClick={() => agir('confirmar', n.id)}>Confirmar presença</Button>}
      {!n.lida && <Button variant="outline" disabled={ocupada || loading} onClick={() => agir('lida', n.id)}>Marcar como lida</Button>}
    </> },
  ];
  return <div className="page">
    <PageHeader title="Notificações" description={!loading && !error ? `Não lidas: ${naoLidas}` : undefined}
      action={<Button variant="outline" onClick={refresh} disabled={ocupada || loading}>Atualizar</Button>} />
    {aviso && <Alert tone={aviso.tom}>{aviso.texto}</Alert>}
    <Card><DataTable caption="Central de notificações" columns={colunas} rows={records} loading={loading}
      error={error} onRetry={refresh} emptyMessage="Você ainda não recebeu notificações." /></Card>
  </div>;
}
