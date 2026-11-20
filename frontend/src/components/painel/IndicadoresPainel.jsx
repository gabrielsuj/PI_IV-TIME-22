import { useId } from 'react';
import { useIndicadores } from '../../hooks/useIndicadores';
import Alert from '../ui/Alert';
import Badge from '../ui/Badge';
import Button from '../ui/Button';
import Card from '../ui/Card';

const METRICAS = [
  { campo: 'ocupacao', titulo: 'Ocupação', apoio: (n, d) => `${n} de ${d} vagas ocupadas` },
  { campo: 'faltas', titulo: 'Faltas', apoio: (n, d) => `Faltas: ${n} · Consultas com desfecho: ${d}` },
  { campo: 'cancelamento', titulo: 'Cancelamentos', apoio: (n, d) => `Canceladas: ${n} · Total de consultas: ${d}` },
  { campo: 'confirmacao', titulo: 'Confirmações', apoio: (n, d) => `Com confirmação registrada: ${n} · Total de consultas: ${d}` },
  { campo: 'preenchimentoFila', titulo: 'Vagas preenchidas pela fila', apoio: (n, d) => `Pela fila: ${n} · Consultas não canceladas: ${d}` },
];
const SITUACOES = {
  SEM_BASE: { rotulo: 'Sem base para cálculo', tone: 'neutral' },
  DADOS_PENDENTES: { rotulo: 'Atualização pendente', tone: 'warning' },
  DADOS_INCOMPLETOS: { rotulo: 'Dados incompletos', tone: 'warning' },
};
const NOTAS = [
  ['medicosSemAgenda', 'Profissionais sem agenda configurada'],
  ['consultasComProjecaoPendente', 'Consultas com histórico em atualização'],
  ['consultasSemBaseHistorica', 'Consultas sem histórico suficiente'],
  ['inscricoesComVinculoInvalido', 'Inscrições com vínculo de consulta inválido'],
];
const percentual = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const numero = new Intl.NumberFormat('pt-BR');
const dataBr = (data) => data.split('-').reverse().join('/');

export default function IndicadoresPainel({ de, ate, medicoId }) {
  const tituloId = useId();
  const { dados, erro, carregando, permitido, atualizar } = useIndicadores({ de, ate, medicoId });
  if (!permitido) return null;

  return (
    <section className="page" aria-labelledby={tituloId} aria-busy={carregando}>
      <header className="page__header">
        <div>
          <h2 id={tituloId}>Indicadores</h2>
          <p className="page__lead">Consultas iniciadas de {dataBr(de)}{de !== ate && ` a ${dataBr(ate)}`} · America/Sao_Paulo</p>
        </div>
        <Button variant="outline" onClick={atualizar} loading={carregando}>Atualizar indicadores</Button>
      </header>
      {carregando && <p role="status">Carregando indicadores…</p>}
      {erro && <Alert action={<Button variant="outline" onClick={atualizar}>Tentar novamente</Button>}>{erro.message}</Alert>}
      {dados && <>
        <div className="record-grid">
          {METRICAS.map(({ campo, titulo, apoio }) => {
            const taxa = dados[campo];
            const situacao = SITUACOES[taxa.situacao];
            return (
              <Card key={campo} title={titulo}>
                <p className="indicador__valor">{taxa.percentual === null ? '—' : `${percentual.format(taxa.percentual)}%`}</p>
                {situacao && <Badge tone={situacao.tone}>{situacao.rotulo}</Badge>}
                <p className="card__description">{apoio(numero.format(taxa.numerador), numero.format(taxa.denominador))}</p>
              </Card>
            );
          })}
        </div>
        {NOTAS.some(([campo]) => dados.qualidade[campo] > 0) && <ul className="permissions-list">
          {NOTAS.filter(([campo]) => dados.qualidade[campo] > 0).map(([campo, rotulo]) => (
            <li key={campo}>{rotulo}: {numero.format(dados.qualidade[campo])}.</li>
          ))}
        </ul>}
        {dados.qualidade.capacidadeEstimada && <p className="page__lead">{dados.qualidade.observacaoCapacidade}</p>}
      </>}
    </section>
  );
}
