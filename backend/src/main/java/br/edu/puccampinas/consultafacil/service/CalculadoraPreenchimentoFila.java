package br.edu.puccampinas.consultafacil.service;

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
 * Origem das reservas mantidas na #36: consultas distintas vinculadas por consultaAgendadaId a
 * inscrições ATENDIDA / consultas não canceladas do recorte. AGENDADA, CONFIRMADA, CONCLUIDA e
 * FALTA permanecem na população; CANCELADA sai do numerador e do denominador, mesmo se a inscrição
 * continuar ATENDIDA. Sinalização de vaga ou notificação não comprova preenchimento.
 *
 * <p>Médico atual e início da consulta definem o recorte, não o médico preferido da inscrição nem
 * atendidaEm. Recebe fontes completas, sem limite 100: todas as consultas do recorte e as consultas
 * referenciadas pelas inscrições recebidas, inclusive fora do recorte. Uma referência não encontrada
 * pode indicar fonte incompleta ou vínculo órfão; a taxa fica indefinida, sem inventar zero.</p>
 * <p>Cálculo puro, sem expirar/revisar fila, sinalizar, reservar ou recuperar histórico. As leituras
 * das fontes cabem ao repositório de indicadores e a autorização ao serviço. Não reconstrói vínculos
 * de inscrições legadas sem consultaAgendadaId e não modifica o fluxo de atendimento existente.</p>
 */
public final class CalculadoraPreenchimentoFila {
    private CalculadoraPreenchimentoFila() {}
    private static final Set<String> STATUS_FILA = Set.of("AGUARDANDO", "SINALIZADA", "ATENDIDA", "CANCELADA", "EXPIRADA");

    public record Resultado(long consultasNaoCanceladas, long preenchidasPelaFila, long inscricoesComVinculoInvalido) {
        /** Vazio: vínculo/fonte insuficiente; taxa 0/0: nenhuma reserva mantida no recorte. */
        public Optional<CalculadoraIndicadores.Taxa> preenchimentoFila() {
            return inscricoesComVinculoInvalido > 0 ? Optional.empty()
                    : Optional.of(new CalculadoraIndicadores.Taxa(preenchidasPelaFila, consultasNaoCanceladas));
        }
    }

    public static Resultado calcular(PeriodoIndicadores periodo, Optional<ObjectId> medicoId,
                                      List<Document> consultas, List<Document> inscricoes) {
        Objects.requireNonNull(periodo, "Período é obrigatório");
        Objects.requireNonNull(medicoId, "Informe o escopo de médicos");
        Objects.requireNonNull(consultas, "Consultas completas são obrigatórias");
        Objects.requireNonNull(inscricoes, "Inscrições completas são obrigatórias");
        Map<ObjectId, Document> porId = documentosUnicos(consultas);
        Set<ObjectId> mantidas = new HashSet<>();
        var inicio = periodo.inicioInclusivo();
        var fim = periodo.fimExclusivo();
        for (var entrada : porId.entrySet()) {
            Document consulta = entrada.getValue();
            var quando = Objects.requireNonNull(consulta.getDate("inicio"), "Consulta sem início").toInstant();
            if (quando.isBefore(inicio) || !quando.isBefore(fim)) continue;
            ObjectId medico = Objects.requireNonNull(consulta.getObjectId("medicoId"), "Consulta sem médico");
            if (medicoId.isPresent() && !medicoId.get().equals(medico)) continue;
            if (StatusConsulta.valueOf(consulta.getString("status")) != StatusConsulta.CANCELADA)
                mantidas.add(entrada.getKey());
        }

        Set<ObjectId> preenchidas = new HashSet<>();
        long invalidos = 0;
        for (Document inscricao : documentosUnicos(inscricoes).values()) {
            String status = inscricao.getString("status");
            if (status == null || !STATUS_FILA.contains(status))
                throw new IllegalArgumentException("Status da fila ausente ou desconhecido");
            if (!"ATENDIDA".equals(status)) continue;
            Object referencia = inscricao.get("consultaAgendadaId");
            if (!(referencia instanceof ObjectId id) || !porId.containsKey(id)) {
                invalidos++;
                continue;
            }
            if (mantidas.contains(id)) preenchidas.add(id);
        }
        return new Resultado(mantidas.size(), preenchidas.size(), invalidos);
    }

    private static Map<ObjectId, Document> documentosUnicos(List<Document> documentos) {
        Map<ObjectId, Document> unicos = new HashMap<>();
        for (Document documento : documentos) {
            ObjectId id = Objects.requireNonNull(documento.getObjectId("_id"), "Documento sem identidade");
            Document anterior = unicos.putIfAbsent(id, documento);
            if (anterior != null && !anterior.equals(documento))
                throw new IllegalArgumentException("Snapshots divergentes para a mesma identidade");
        }
        return unicos;
    }
}
