package br.edu.puccampinas.consultafacil.repository;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.service.PeriodoIndicadores;
import org.bson.types.ObjectId;

import java.util.Objects;
import java.util.Optional;

/**
 * Leitura agregada interna da #36, sem documentos pessoais ou limite da listagem de consultas.
 * O serviço resolve o escopo autorizado antes da leitura. medicoId vazio significa todos os médicos;
 * um filtro de cadastro inexistente deve gerar 404, não um resumo vazio. Sem filtro, ausência real
 * de consultas pode produzir contagens zero. Falha/ausência da fonte não deve virar zero.
 * Não expira/revisa fila, recupera histórico ou escreve em qualquer coleção.
 */
@FunctionalInterface
public interface IndicadoresLeituraRepository {
    Resumo ler(PeriodoIndicadores periodo, Optional<ObjectId> medicoId);

    record Consultas(long agendadas, long confirmadas, long concluidas, long faltas, long canceladas) {}
    record Capacidade(long total, long ocupadas, long medicosSemAgenda) {}
    record Confirmacao(long registradas, long comProjecaoPendente, long semBaseHistorica) {}
    record Fila(long preenchidas, long inscricoesComVinculoInvalido) {}

    record Resumo(Consultas consultas, Capacidade capacidade, Confirmacao confirmacao, Fila fila) {
        public Resumo {
            Objects.requireNonNull(consultas, "Contagens de consultas são obrigatórias");
            Objects.requireNonNull(capacidade, "Contagens de capacidade são obrigatórias");
            Objects.requireNonNull(confirmacao, "Contagens de confirmação são obrigatórias");
            Objects.requireNonNull(fila, "Contagens da fila são obrigatórias");
        }
    }

    static IndicadoresLeituraRepository pendente() {
        // Necessita leitura agregada da #36 (etapa 6).
        return (periodo, medicoId) -> { throw new ApiException(503, "Indicadores temporariamente indisponíveis."); };
    }
}
