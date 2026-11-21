/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

package br.edu.puccampinas.consultafacil.repository;

import br.edu.puccampinas.consultafacil.service.PeriodoIndicadores;
import org.bson.types.ObjectId;

import java.util.Objects;
import java.util.Optional;

@FunctionalInterface
public interface IndicadoresLeituraRepository {
    Resumo ler(PeriodoIndicadores periodo, Optional<ObjectId> medicoId);

    record Consultas(long agendadas, long confirmadas, long concluidas, long faltas, long canceladas) {}
    record Capacidade(long total, long ocupadas, long medicosSemAgenda) {}
    record Confirmacao(long registradas, long comProjecaoPendente, long semBaseHistorica) {}
    record Fila(long preenchidas, long inscricoesComVinculoInvalido) {}

    // O resumo reúne as fontes do mesmo recorte antes do cálculo, mantendo as
    // contagens de qualidade junto dos totais consolidados.
    record Resumo(Consultas consultas, Capacidade capacidade, Confirmacao confirmacao, Fila fila) {
        public Resumo {
            Objects.requireNonNull(consultas, "Contagens de consultas são obrigatórias");
            Objects.requireNonNull(capacidade, "Contagens de capacidade são obrigatórias");
            Objects.requireNonNull(confirmacao, "Contagens de confirmação são obrigatórias");
            Objects.requireNonNull(fila, "Contagens da fila são obrigatórias");
        }
    }
}
