/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.domain.enums.StatusConsulta;
import br.edu.puccampinas.consultafacil.service.CalculadoraDisponibilidade.Vaga;
import org.bson.Document;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class CalculadoraCapacidade {
    private CalculadoraCapacidade() {}

    public record Resultado(boolean semAgenda, long capacidade, long vagasOcupadas) {
        public CalculadoraIndicadores.Taxa ocupacao() {
            return new CalculadoraIndicadores.Taxa(vagasOcupadas, capacidade);
        }
    }

    public static Resultado calcular(Optional<Document> agenda, PeriodoIndicadores periodo,
                                      List<Document> bloqueios, List<Document> consultas) {
        Objects.requireNonNull(agenda, "Informe a existência da agenda");
        Objects.requireNonNull(periodo, "Período é obrigatório");
        Objects.requireNonNull(bloqueios, "Bloqueios completos são obrigatórios");
        Objects.requireNonNull(consultas, "Consultas completas são obrigatórias");
        if (agenda.isEmpty()) return new Resultado(true, 0, 0);

        // A capacidade inclui os slots do período, mesmo passados; a ocupação conta
        // cada slot uma vez quando existe reserva não cancelada.
        List<Vaga> capacidade = slotsNoPeriodo(agenda.get(), periodo, bloqueios);
        List<Document> mantidas = consultas.stream()
                .filter(consulta -> StatusConsulta.valueOf(consulta.getString("status")) != StatusConsulta.CANCELADA)
                .toList();
        long ocupadas = capacidade.stream()
                .filter(vaga -> CalculadoraDisponibilidade.sobrepoe(vaga.inicio(), vaga.fim(), mantidas)).count();
        return new Resultado(false, capacidade.size(), ocupadas);
    }

    public static List<Vaga> slotsNoPeriodo(Document agenda, PeriodoIndicadores periodo, List<Document> bloqueios) {
        Objects.requireNonNull(agenda);
        Objects.requireNonNull(periodo);
        Objects.requireNonNull(bloqueios);
        ZoneId fuso = ZoneId.of(agenda.getString("fusoHorario"));
        var inicio = periodo.inicioInclusivo();
        var fim = periodo.fimExclusivo();
        LocalDate primeiroDia = inicio.atZone(fuso).toLocalDate();
        LocalDate ultimoDia = fim.minusNanos(1).atZone(fuso).toLocalDate();
        List<Vaga> slots = new ArrayList<>();
        for (LocalDate dia = primeiroDia; !dia.isAfter(ultimoDia); dia = dia.plusDays(1)) {
            for (Vaga vaga : CalculadoraDisponibilidade.capacidadeDoDia(agenda, dia, bloqueios))
                if (!vaga.inicio().isBefore(inicio) && vaga.inicio().isBefore(fim)) slots.add(vaga);
        }
        return slots.stream().distinct().sorted(Comparator.comparing(Vaga::inicio)).toList();
    }
}
