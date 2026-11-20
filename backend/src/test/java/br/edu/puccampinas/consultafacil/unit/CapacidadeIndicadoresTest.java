package br.edu.puccampinas.consultafacil.unit;

import br.edu.puccampinas.consultafacil.service.CalculadoraCapacidade;
import br.edu.puccampinas.consultafacil.service.CalculadoraDisponibilidade;
import br.edu.puccampinas.consultafacil.service.CalculadoraIndicadores;
import br.edu.puccampinas.consultafacil.service.PeriodoIndicadores;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CapacidadeIndicadoresTest {
    private static final LocalDate DIA = LocalDate.of(2026, 11, 23);
    private static final PeriodoIndicadores PERIODO = new PeriodoIndicadores(DIA, DIA);

    private static Document agenda(String fuso, int duracao, int diaSemana, int inicio, int fim) {
        return new Document("fusoHorario", fuso).append("duracaoMinutos", duracao)
                .append("jornadas", List.of(new Document("diaSemana", diaSemana)
                        .append("faixas", List.of(new Document("inicioMinuto", inicio).append("fimMinuto", fim)))));
    }

    private static Document agendaDaSegunda() { return agenda("America/Sao_Paulo", 30, 1, 480, 780); }

    private static Document intervalo(String inicio, String fim) {
        return new Document("inicio", Date.from(Instant.parse(inicio))).append("fim", Date.from(Instant.parse(fim)));
    }

    private static Document consulta(String inicio, String fim, String status) {
        return intervalo(inicio, fim).append("status", status);
    }

    @Test void fixtureDeCapacidadeEOcupacaoConfereComAFormulaDaEtapaAnterior() {
        var agenda = agendaDaSegunda();
        var bloqueio = intervalo("2026-11-23T12:00:00Z", "2026-11-23T13:00:00Z");
        var consultas = List.of(
                consulta("2026-11-23T11:00:00Z", "2026-11-23T11:30:00Z", "AGENDADA"),
                consulta("2026-11-23T11:30:00Z", "2026-11-23T12:00:00Z", "CONFIRMADA"),
                consulta("2026-11-23T13:00:00Z", "2026-11-23T14:00:00Z", "CONCLUIDA"),
                consulta("2026-11-23T14:00:00Z", "2026-11-23T14:30:00Z", "CONCLUIDA"),
                consulta("2026-11-23T14:30:00Z", "2026-11-23T15:00:00Z", "FALTA"),
                consulta("2026-11-23T15:00:00Z", "2026-11-23T15:30:00Z", "CANCELADA"));
        var resultado = CalculadoraCapacidade.calcular(Optional.of(agenda), PERIODO, List.of(bloqueio), consultas);
        assertFalse(resultado.semAgenda());
        assertEquals(8, resultado.capacidade());
        assertEquals(6, resultado.vagasOcupadas());
        assertEquals(new BigDecimal("75.00"), resultado.ocupacao().percentual());
        var indicadores = CalculadoraIndicadores.calcular(PERIODO, new CalculadoraIndicadores.Contagens(
                resultado.capacidade(), resultado.vagasOcupadas(), 1, 1, 2, 1, 1, 4, 2));
        assertEquals(resultado.ocupacao(), indicadores.ocupacao());
    }

    @Test void capacidadeIncluiPassadoEReservasEnquantoDisponibilidadeContinuaFiltrandoAmbos() {
        var agenda = agendaDaSegunda();
        var consulta = consulta("2026-11-23T11:00:00Z", "2026-11-23T11:30:00Z", "AGENDADA");
        var capacidade = CalculadoraDisponibilidade.capacidadeDoDia(agenda, DIA, List.of());
        assertEquals(10, capacidade.size());
        var livres = CalculadoraDisponibilidade.calcular(agenda, DIA, List.of(), List.of(consulta), Instant.parse("2026-11-23T11:00:00Z"));
        assertEquals(9, livres.size());
        assertEquals(Instant.parse("2026-11-23T11:30:00Z"), livres.getFirst().inicio());
        assertTrue(CalculadoraDisponibilidade.calcular(agenda, DIA, List.of(), List.of(consulta),
                Instant.parse("2026-11-24T03:00:00Z")).isEmpty());
        assertEquals(capacidade, CalculadoraDisponibilidade.capacidadeDoDia(agenda, DIA, List.of()));
    }

    @Test void bloqueiosParciaisRetiramSlotsInteirosSemContarDuasVezesASobreposicao() {
        var agenda = agendaDaSegunda();
        var bloqueios = List.of(intervalo("2026-11-23T11:15:00Z", "2026-11-23T11:45:00Z"),
                intervalo("2026-11-23T11:25:00Z", "2026-11-23T11:35:00Z"));
        var capacidade = CalculadoraDisponibilidade.capacidadeDoDia(agenda, DIA, bloqueios);
        assertEquals(8, capacidade.size());
        assertEquals(Instant.parse("2026-11-23T12:00:00Z"), capacidade.getFirst().inicio());
    }

    @Test void intervalosApenasEncostadosNaoBloqueiamNemOcupamSlots() {
        var agenda = agenda("America/Sao_Paulo", 30, 1, 480, 510);
        var resultado = CalculadoraCapacidade.calcular(Optional.of(agenda), PERIODO,
                List.of(intervalo("2026-11-23T10:30:00Z", "2026-11-23T11:00:00Z")),
                List.of(consulta("2026-11-23T11:30:00Z", "2026-11-23T12:00:00Z", "AGENDADA")));
        assertEquals(1, resultado.capacidade());
        assertEquals(0, resultado.vagasOcupadas());
    }

    @Test void reservasRepetidasESobrepostasOcupamCadaSlotUmaUnicaVez() {
        var agenda = agenda("America/Sao_Paulo", 30, 1, 480, 540);
        var consulta = consulta("2026-11-23T11:15:00Z", "2026-11-23T11:45:00Z", "CONFIRMADA");
        var resultado = CalculadoraCapacidade.calcular(Optional.of(agenda), PERIODO, List.of(), List.of(consulta, consulta,
                consulta("2026-11-23T11:20:00Z", "2026-11-23T11:40:00Z", "FALTA")));
        assertEquals(2, resultado.capacidade());
        assertEquals(2, resultado.vagasOcupadas());
        assertEquals(new BigDecimal("100.00"), resultado.ocupacao().percentual());
    }

    @Test void mesmoReservaForaDaJornadaOuSobreBloqueioNaoCriaCapacidade() {
        var resultado = CalculadoraCapacidade.calcular(Optional.of(agendaDaSegunda()), PERIODO,
                List.of(intervalo("2026-11-23T11:00:00Z", "2026-11-23T16:00:00Z")),
                List.of(consulta("2026-11-23T11:00:00Z", "2026-11-23T17:00:00Z", "CONCLUIDA")));
        assertEquals(0, resultado.capacidade());
        assertEquals(0, resultado.vagasOcupadas());
        assertNull(resultado.ocupacao().percentual());
    }

    @Test void ausenciaDeAgendaEDiaSemJornadaSaoDistintosDeFalhaNaFonte() {
        var ausente = CalculadoraCapacidade.calcular(Optional.empty(), PERIODO, List.of(), List.of());
        assertTrue(ausente.semAgenda());
        assertEquals(0, ausente.capacidade());
        assertNull(ausente.ocupacao().percentual());
        var semJornada = CalculadoraCapacidade.calcular(Optional.of(agendaDaSegunda()),
                PeriodoIndicadores.de("2026-11-24", "2026-11-24"), List.of(), List.of());
        assertFalse(semJornada.semAgenda());
        assertEquals(0, semJornada.capacidade());
        assertThrows(NullPointerException.class, () -> CalculadoraCapacidade.calcular(null, PERIODO, List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> CalculadoraCapacidade.calcular(Optional.of(agendaDaSegunda()), PERIODO, null, List.of()));
        assertThrows(NullPointerException.class, () -> CalculadoraCapacidade.calcular(Optional.of(agendaDaSegunda()), PERIODO, List.of(), null));
    }

    @Test void somaSomenteDiasDaJornadaNoPeriodo() {
        var resultado = CalculadoraCapacidade.calcular(Optional.of(agendaDaSegunda()),
                PeriodoIndicadores.de("2026-11-23", "2026-11-30"), List.of(), List.of());
        assertEquals(20, resultado.capacidade());
    }

    @Test void recorteDaClinicaIncluiDiasAdjacentesNoFusoDaAgendaEExcluiFimDaJanela() {
        var agenda = agenda("UTC", 30, 1, 0, 240);
        var segunda = agenda.getList("jornadas", Document.class).getFirst();
        agenda.put("jornadas", List.of(segunda, new Document(segunda).append("diaSemana", 2)));
        var resultado = CalculadoraCapacidade.calcular(Optional.of(agenda), PERIODO, List.of(), List.of(
                consulta("2026-11-23T02:30:00Z", "2026-11-23T03:30:00Z", "AGENDADA"),
                consulta("2026-11-24T03:00:00Z", "2026-11-24T03:30:00Z", "AGENDADA")));
        assertEquals(8, resultado.capacidade());
        assertEquals(1, resultado.vagasOcupadas());
    }

    @Test void reservaAposOFimDoPeriodoPodeSobreporUltimoSlotIniciadoNaJanela() {
        var agenda = agenda("UTC", 60, 2, 150, 210);
        var resultado = CalculadoraCapacidade.calcular(Optional.of(agenda), PERIODO, List.of(), List.of(
                consulta("2026-11-24T03:00:00Z", "2026-11-24T03:30:00Z", "CONFIRMADA")));
        assertEquals(1, resultado.capacidade());
        assertEquals(1, resultado.vagasOcupadas());
    }

    @Test void duracaoExigeSlotCompletoENenhumaFonteRecebeAlteracoes() {
        var agenda = agenda("America/Sao_Paulo", 30, 1, 480, 530);
        var bloqueios = List.of(intervalo("2026-11-23T11:30:00Z", "2026-11-23T12:00:00Z"));
        var consultas = List.of(consulta("2026-11-23T11:00:00Z", "2026-11-23T11:30:00Z", "CANCELADA"));
        var antes = new Document("agenda", agenda).append("bloqueios", bloqueios).append("consultas", consultas).toJson();
        var resultado = CalculadoraCapacidade.calcular(Optional.of(agenda), PERIODO, bloqueios, consultas);
        assertEquals(1, resultado.capacidade());
        assertEquals(0, resultado.vagasOcupadas());
        assertEquals(new BigDecimal("0.00"), resultado.ocupacao().percentual());
        assertEquals(antes, new Document("agenda", agenda).append("bloqueios", bloqueios).append("consultas", consultas).toJson());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -30, 4, 241, Integer.MAX_VALUE})
    void duracaoInvalidaNaoViraCapacidadeZeroNemLacoSemFim(Integer duracao) {
        var agenda = agendaDaSegunda().append("duracaoMinutos", duracao);
        assertThrows(IllegalArgumentException.class, () -> CalculadoraDisponibilidade.capacidadeDoDia(agenda, DIA, List.of()));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "DESCONHECIDO"})
    void statusInvalidoNaoEhInterpretadoComoReservaLivre(String status) {
        var consulta = consulta("2026-11-23T11:00:00Z", "2026-11-23T11:30:00Z", status);
        Class<? extends RuntimeException> tipo = status == null ? NullPointerException.class : IllegalArgumentException.class;
        assertThrows(tipo, () -> CalculadoraCapacidade.calcular(Optional.of(agendaDaSegunda()), PERIODO, List.of(), List.of(consulta)));
    }
}
