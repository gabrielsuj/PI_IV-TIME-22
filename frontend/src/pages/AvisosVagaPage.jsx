/*
Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import Badge from '../components/ui/Badge';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';
import DataTable from '../components/ui/DataTable';
import PageHeader from '../components/ui/PageHeader';
import { listarAvisosVaga } from '../services/avisosVagaService';
import { explicarExpiracao } from '../utils/avisoVaga';
import { dataHoraDaConsulta, FUSO_CLINICA } from '../utils/consulta';

const INTERVALO_ATUALIZACAO = 15_000;
const validade = new Intl.DateTimeFormat('pt-BR', {
  timeZone: FUSO_CLINICA, dateStyle: 'short', timeStyle: 'short',
});
const STATUS = {
  DISPONIVEL: { texto: 'Disponível', tom: 'info' },
  EXPIRADO: { texto: 'Expirado', tom: 'neutral' },
  AGENDADO: { texto: 'Agendado', tom: 'success' },
};

export default function AvisosVagaPage() {
  const [lista, setLista] = useState({ avisos: [], carregando: true, erro: null });
  const [revisao, setRevisao] = useState(0);
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
      return <Button to="/fila-espera" variant="outline" size="sm">Ver fila de espera</Button>;
    } },
  ];

  return (
    <div className="page">
      <PageHeader title="Avisos de vaga" description="Acompanhe as vagas liberadas para você pela fila de espera. Para agendar, entre em contato com a secretaria."
        action={<Button variant="outline" onClick={atualizar}>Atualizar avisos</Button>} />
      <Card>
        <DataTable caption="Avisos de vaga" columns={colunas} rows={lista.avisos} loading={lista.carregando}
          error={lista.erro} onRetry={atualizar} emptyMessage={(
            <>
              Você ainda não recebeu avisos de vaga. Entre na <Link to="/fila-espera">fila de espera</Link> para
              ser avisado quando um horário compatível for liberado.
            </>
          )} />
      </Card>
    </div>
  );
}
