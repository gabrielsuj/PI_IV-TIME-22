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

public final class CalculadoraPreenchimentoFila {
    private CalculadoraPreenchimentoFila() {}
    private static final Set<String> STATUS_FILA = Set.of("AGUARDANDO", "SINALIZADA", "ATENDIDA", "CANCELADA", "EXPIRADA");

    public record Resultado(long consultasNaoCanceladas, long preenchidasPelaFila, long inscricoesComVinculoInvalido) {
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
