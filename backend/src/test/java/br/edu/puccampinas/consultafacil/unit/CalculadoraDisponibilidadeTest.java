package br.edu.puccampinas.consultafacil.unit;

import br.edu.puccampinas.consultafacil.service.CalculadoraDisponibilidade;
import br.edu.puccampinas.consultafacil.service.CalculadoraDisponibilidade.Vaga;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cálculo de disponibilidade (#20), sem banco.
 * Usa a agenda da Ana do seed (database/seed/01-agenda.js) e o cenário de referência
 * de docs/modelagem/medicos-agenda.md §6. Horários em UTC: 08:00 em São Paulo = 11:00Z.
 */
class CalculadoraDisponibilidadeTest {
    /** Segunda-feira do cenário de referência. */
    private static final LocalDate SEGUNDA = LocalDate.of(2026, 10, 26);
    /** Antes de qualquer horário do cenário: nenhum horário é descartado por já ter passado. */
    private static final Instant ANTES = Instant.parse("2026-10-20T00:00:00Z");

    /** Ana: segunda 08h–12h e 13h–17h, quarta 08h–12h, consultas de 30 min. */
    private static Document agendaDaAna() {
        return new Document("fusoHorario", "America/Sao_Paulo")
                .append("duracaoMinutos", 30)
                .append("jornadas", List.of(
                        new Document("diaSemana", 1).append("faixas", List.of(faixa(480, 720), faixa(780, 1020))),
                        new Document("diaSemana", 3).append("faixas", List.of(faixa(480, 720)))));
    }

    private static Document faixa(int inicioMinuto, int fimMinuto) {
        return new Document("inicioMinuto", inicioMinuto).append("fimMinuto", fimMinuto);
    }

    /** Bloqueio ou consulta: só os campos que o cálculo usa. */
    private static Document periodo(String inicio, String fim) {
        return new Document("inicio", Date.from(Instant.parse(inicio))).append("fim", Date.from(Instant.parse(fim)));
    }

    private static List<String> inicios(List<Vaga> vagas) {
        List<String> lista = new ArrayList<>();
        for (Vaga v : vagas) lista.add(v.inicio().toString());
        return lista;
    }

    @Test
    void deveGerarHorariosDaJornadaPelaDuracao() {
        List<Vaga> vagas = CalculadoraDisponibilidade.calcular(agendaDaAna(), SEGUNDA, List.of(), List.of(), ANTES);

        // 8 horários de manhã (08h–12h) + 8 à tarde (13h–17h)
        assertEquals(16, vagas.size());
        assertEquals("2026-10-26T11:00:00Z", vagas.get(0).inicio().toString());   // 08:00
        assertEquals("2026-10-26T11:30:00Z", vagas.get(0).fim().toString());      // 08:30
        assertEquals("2026-10-26T19:30:00Z", vagas.get(15).inicio().toString());  // 16:30, o último
        assertFalse(inicios(vagas).contains("2026-10-26T15:00:00Z"), "12h–13h é intervalo, fora da jornada");
    }

    /** Cenário de referência: bloqueio das 09h às 10h → manhã com 08:00, 08:30, 10:00, 10:30, 11:00 e 11:30. */
    @Test
    void deveRemoverHorariosDoPeriodoBloqueado() {
        var bloqueio = periodo("2026-10-26T12:00:00Z", "2026-10-26T13:00:00Z");   // 09h–10h

        List<String> manha = inicios(CalculadoraDisponibilidade.calcular(
                agendaDaAna(), SEGUNDA, List.of(bloqueio), List.of(), ANTES)).subList(0, 6);

        assertEquals(List.of(
                "2026-10-26T11:00:00Z", "2026-10-26T11:30:00Z",   // 08:00, 08:30
                "2026-10-26T13:00:00Z", "2026-10-26T13:30:00Z",   // 10:00, 10:30 (o bloqueio termina às 10h: fim excluído)
                "2026-10-26T14:00:00Z", "2026-10-26T14:30:00Z"),  // 11:00, 11:30
                manha);
    }

    @Test
    void deveRemoverHorarioComConsultaAtiva() {
        var consulta = periodo("2026-10-26T11:30:00Z", "2026-10-26T12:00:00Z");   // 08:30–09:00

        List<String> inicios = inicios(CalculadoraDisponibilidade.calcular(
                agendaDaAna(), SEGUNDA, List.of(), List.of(consulta), ANTES));

        assertEquals(15, inicios.size());
        assertFalse(inicios.contains("2026-10-26T11:30:00Z"));
    }

    @Test
    void deveRemoverHorarioQueSoEncostaEmParteDaConsulta() {
        // Consulta antiga de 08:15–08:45 (marcada quando a duração era outra): derruba 08:00 e 08:30.
        var consulta = periodo("2026-10-26T11:15:00Z", "2026-10-26T11:45:00Z");

        List<String> inicios = inicios(CalculadoraDisponibilidade.calcular(
                agendaDaAna(), SEGUNDA, List.of(), List.of(consulta), ANTES));

        assertFalse(inicios.contains("2026-10-26T11:00:00Z"));
        assertFalse(inicios.contains("2026-10-26T11:30:00Z"));
        assertTrue(inicios.contains("2026-10-26T12:00:00Z"));
    }

    @Test
    void naoDeveOferecerHorarioEmDiaForaDaJornada() {
        LocalDate terca = LocalDate.of(2026, 10, 27);   // Ana não atende às terças
        assertTrue(CalculadoraDisponibilidade.calcular(agendaDaAna(), terca, List.of(), List.of(), ANTES).isEmpty());
    }

    @Test
    void naoDeveOferecerHorarioQueJaComecou() {
        Instant agora = Instant.parse("2026-10-26T14:10:00Z");   // 11:10 em São Paulo

        List<String> inicios = inicios(CalculadoraDisponibilidade.calcular(
                agendaDaAna(), SEGUNDA, List.of(), List.of(), agora));

        assertEquals("2026-10-26T14:30:00Z", inicios.get(0));   // 11:30 é o primeiro que ainda não começou
    }

    @Test
    void naoDeveOferecerHorarioQueNaoCabeInteiroNaFaixa() {
        // Faixa de 08h às 08h50 com consultas de 30 min: só cabe 08:00–08:30.
        Document agenda = new Document("fusoHorario", "America/Sao_Paulo").append("duracaoMinutos", 30)
                .append("jornadas", List.of(new Document("diaSemana", 1).append("faixas", List.of(faixa(480, 530)))));

        List<Vaga> vagas = CalculadoraDisponibilidade.calcular(agenda, SEGUNDA, List.of(), List.of(), ANTES);

        assertEquals(1, vagas.size());
        assertEquals("2026-10-26T11:30:00Z", vagas.get(0).fim().toString());
    }
}
