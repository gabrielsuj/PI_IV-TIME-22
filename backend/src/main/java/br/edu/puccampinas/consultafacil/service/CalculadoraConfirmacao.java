package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.domain.enums.EventoHistorico;
import br.edu.puccampinas.consultafacil.domain.enums.StatusConsulta;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Confirmação registrada da #36, por consultas distintas iniciadas no período e pelo médico atual
 * da consulta (medicoId do cadastro, não do login). Uma confirmação continua contando após conclusão,
 * falta, cancelamento ou remarcação que mantenha a consulta no recorte. A data do evento e os dados
 * antigos de médico/horário no histórico não redefinem esse recorte.
 *
 * <p>Recebe fontes completas, sem o limite 100 da listagem/timeline. Não busca, grava ou processa
 * pendências; a leitura das fontes e a autorização serão integradas nas próximas etapas da #36.
 * Eventos CONFIRMACAO são deduplicados por consultaId. O status atual não substitui o evento.</p>
 * <p>Expõe a contagem comprovada pelos eventos projetados. Havendo caixa de pendências não vazia,
 * suspende a taxa até uma nova leitura completa. Consulta sem CRIACAO nem CONFIRMACAO projetada
 * também não tem base suficiente para afirmar ausência de confirmação (ex.: legado). Não fabrica
 * eventos nem transforma esses casos em 0% verdadeiro. Inconsistências estruturais são recusadas.</p>
 */
public final class CalculadoraConfirmacao {
    private CalculadoraConfirmacao() {}

    public record Resultado(long totalConsultas, long comConfirmacaoRegistrada,
                            long consultasComProjecaoPendente, long consultasSemBaseHistorica) {
        public boolean atualizacaoPendente() { return consultasComProjecaoPendente > 0; }

        /** Vazio significa fonte ainda insuficiente; taxa 0/0 significa período sem consultas. */
        public Optional<CalculadoraIndicadores.Taxa> confirmacao() {
            return atualizacaoPendente() || consultasSemBaseHistorica > 0 ? Optional.empty()
                    : Optional.of(new CalculadoraIndicadores.Taxa(comConfirmacaoRegistrada, totalConsultas));
        }
    }

    public static Resultado calcular(PeriodoIndicadores periodo, Optional<ObjectId> medicoId,
                                      List<Document> consultas, List<Document> eventosProjetados) {
        Objects.requireNonNull(periodo, "Período é obrigatório");
        Objects.requireNonNull(medicoId, "Informe o escopo de médicos");
        Objects.requireNonNull(consultas, "Consultas completas são obrigatórias");
        Objects.requireNonNull(eventosProjetados, "Histórico completo é obrigatório");
        var inicio = periodo.inicioInclusivo();
        var fim = periodo.fimExclusivo();
        Map<ObjectId, Document> selecionadas = new HashMap<>();
        for (Document consulta : consultas) {
            var quando = Objects.requireNonNull(consulta.getDate("inicio"), "Consulta sem início").toInstant();
            if (quando.isBefore(inicio) || !quando.isBefore(fim)) continue;
            ObjectId medico = Objects.requireNonNull(consulta.getObjectId("medicoId"), "Consulta sem médico");
            if (medicoId.isPresent() && !medicoId.get().equals(medico)) continue;
            StatusConsulta.valueOf(consulta.getString("status"));
            ObjectId id = Objects.requireNonNull(consulta.getObjectId("_id"), "Consulta sem identidade");
            Document anterior = selecionadas.putIfAbsent(id, consulta);
            if (anterior != null && !anterior.equals(consulta))
                throw new IllegalArgumentException("Snapshots divergentes para a mesma consulta");
        }

        Set<ObjectId> confirmadas = new HashSet<>();
        Set<ObjectId> comCriacao = new HashSet<>();
        for (Document evento : eventosProjetados) {
            String tipo = evento.getString("evento");
            if (!EventoHistorico.CONFIRMACAO.name().equals(tipo) && !EventoHistorico.CRIACAO.name().equals(tipo)) continue;
            ObjectId id = Objects.requireNonNull(evento.getObjectId("consultaId"), "Evento sem referência à consulta");
            if (!selecionadas.containsKey(id)) continue;
            if (EventoHistorico.CONFIRMACAO.name().equals(tipo)) confirmadas.add(id);
            else comCriacao.add(id);
        }
        long pendentes = selecionadas.values().stream()
                .filter(consulta -> !consulta.getList("eventosPendentes", Document.class, List.of()).isEmpty()).count();
        long semBase = selecionadas.keySet().stream()
                .filter(id -> !comCriacao.contains(id) && !confirmadas.contains(id)).count();
        return new Resultado(selecionadas.size(), confirmadas.size(), pendentes, semBase);
    }
}
