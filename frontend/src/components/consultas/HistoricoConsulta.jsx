import { useEffect, useRef } from 'react';
import { useHistoricoConsulta } from '../../hooks/useHistoricoConsulta';
import { dataHoraDoHistorico, descricaoDaAlteracao, EVENTOS_HISTORICO, responsavelDoHistorico, statusDoHistorico } from '../../utils/historicoConsulta';
import { FUSO_CLINICA } from '../../utils/consulta';
import Alert from '../ui/Alert';
import Button from '../ui/Button';
import Card from '../ui/Card';

export default function HistoricoConsulta({ consultaId, revisao }) {
  return <ConteudoHistorico key={`${consultaId}|${revisao}`} consultaId={consultaId} />;
}

function ConteudoHistorico({ consultaId }) {
  const { eventos, pagina, carregando, erro, primeiroNovoId, atualizar, tentarNovamente, carregarMais } = useHistoricoConsulta(consultaId);
  const erroRef = useRef(null);
  const novoEvento = useRef(null);
  const acessoRecusado = erro && [400, 403, 404].includes(erro.status);

  useEffect(() => { if (erro) erroRef.current?.focus(); }, [erro]);
  useEffect(() => { if (!carregando && !erro && primeiroNovoId) novoEvento.current?.focus(); }, [carregando, erro, primeiroNovoId]);

  const mensagem = erro?.status === 403 ? 'Você não tem acesso ao histórico desta consulta.'
    : erro?.status === 404 ? 'Não foi possível encontrar a consulta para exibir seu histórico.'
      : erro?.status === 400 ? 'Não foi possível consultar o histórico deste endereço.'
        : erro?.message || 'Não foi possível carregar o histórico. Tente novamente.';

  return (
    <Card title="Histórico" className="historico-card" aria-busy={carregando || undefined}
      description={`Ações registradas para esta consulta. Horários em ${FUSO_CLINICA}.`}
      action={!acessoRecusado && <Button variant="outline" disabled={carregando} onClick={atualizar}>Atualizar histórico</Button>}>
      {carregando && <p role="status">{eventos.length ? 'Atualizando histórico…' : 'Carregando histórico…'}</p>}
      {erro && <Alert ref={erroRef} tabIndex={-1}
        action={!acessoRecusado && <Button variant="outline" onClick={tentarNovamente}>Tentar novamente</Button>}>
        {mensagem} {eventos.length > 0 && 'Os eventos já exibidos foram mantidos; a atualização não foi concluída.'}
      </Alert>}
      {!carregando && !erro && eventos.length === 0 && <p role="status">Nenhum evento disponível no momento.</p>}
      {eventos.length > 0 && <ol className="historico-lista" aria-label="Eventos do histórico">
        {eventos.map((evento) => {
          const quando = dataHoraDoHistorico(evento.ocorridoEm);
          return <li className="historico-item" key={evento.id}>
            <h3 tabIndex={-1} ref={evento.id === primeiroNovoId ? novoEvento : null}>
              {Object.hasOwn(EVENTOS_HISTORICO, evento.evento) ? EVENTOS_HISTORICO[evento.evento] : 'Evento do histórico'}
            </h3>
            <p className="historico-data">{quando ? <time dateTime={evento.ocorridoEm}>{quando}</time> : 'Data não informada'}</p>
            <dl className="historico-dados">
              <div><dt>Responsável</dt><dd>{responsavelDoHistorico(evento.ator)}</dd></div>
              <div><dt>Status anterior</dt><dd>{evento.evento === 'CRIACAO' && !evento.statusAnterior ? 'Não se aplica' : statusDoHistorico(evento.statusAnterior)}</dd></div>
              <div><dt>Novo status</dt><dd>{statusDoHistorico(evento.statusNovo)}</dd></div>
            </dl>
            {evento.evento === 'CANCELAMENTO' && <p><strong>Motivo: </strong>{typeof evento.motivo === 'string' && evento.motivo.trim() ? evento.motivo : 'Não informado'}</p>}
            {evento.evento === 'ALTERACAO' && (Array.isArray(evento.alteracoes) && evento.alteracoes.length > 0
              ? <ul className="historico-alteracoes" aria-label="Dados alterados">{evento.alteracoes.map((alteracao, indice) => <li key={indice}>{descricaoDaAlteracao(alteracao)}</li>)}</ul>
              : <p>Detalhes da alteração não disponíveis.</p>)}
            {evento.evento === 'SINALIZACAO_FILA' && <p>{Number.isInteger(evento.totalSinalizados) && evento.totalSinalizados >= 0
              ? `Interessados sinalizados: ${evento.totalSinalizados}.` : 'Total de sinalizados não informado.'}</p>}
          </li>;
        })}
      </ol>}
      {pagina?.temMais && !erro && <div className="form-actions">
        <Button variant="outline" disabled={carregando} loading={carregando} onClick={carregarMais}>Carregar mais eventos</Button>
      </div>}
      {!acessoRecusado && <p className="form-note">Os nomes refletem o cadastro atual. Se uma ação recente não aparecer, atualize o histórico.</p>}
    </Card>
  );
}
