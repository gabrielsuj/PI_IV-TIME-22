package br.edu.puccampinas.consultafacil.unit;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.service.CalculadoraIndicadores;
import br.edu.puccampinas.consultafacil.service.CalculadoraIndicadores.Contagens;
import br.edu.puccampinas.consultafacil.service.CalculadoraIndicadores.Taxa;
import br.edu.puccampinas.consultafacil.service.PeriodoIndicadores;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IndicadoresTest {
    private static final PeriodoIndicadores PERIODO = PeriodoIndicadores.de("2026-11-20", "2026-11-20");

    @Test void fixtureManualConfereCadaFormulaEContagensDeApoio() {
        var base = new Contagens(8, 6, 1, 1, 2, 1, 1, 4, 2);
        var resultado = CalculadoraIndicadores.calcular(PERIODO, base);
        assertEquals(PERIODO, resultado.periodo());
        assertEquals(base, resultado.contagens());
        assertEquals(6, base.totalConsultas());
        assertEquals(5, base.consultasNaoCanceladas());
        assertEquals(3, base.consultasComDesfecho());
        conferir(resultado.ocupacao(), 6, 8, "75.00");
        conferir(resultado.faltas(), 1, 3, "33.33");
        conferir(resultado.cancelamento(), 1, 6, "16.67");
        conferir(resultado.confirmacao(), 4, 6, "66.67");
        conferir(resultado.preenchimentoFila(), 2, 5, "40.00");
    }

    @Test void periodoSemCapacidadeNemConsultasNaoFingePercentuaisZero() {
        var resultado = CalculadoraIndicadores.calcular(PERIODO, new Contagens(0, 0, 0, 0, 0, 0, 0, 0, 0));
        for (Taxa taxa : List.of(resultado.ocupacao(), resultado.faltas(), resultado.cancelamento(),
                resultado.confirmacao(), resultado.preenchimentoFila())) {
            assertEquals(0, taxa.numerador());
            assertEquals(0, taxa.denominador());
            assertNull(taxa.percentual());
        }
    }

    @Test void agendaSemReservasTemOcupacaoZeroComBaseReal() {
        var resultado = CalculadoraIndicadores.calcular(PERIODO, new Contagens(8, 0, 0, 0, 0, 0, 0, 0, 0));
        conferir(resultado.ocupacao(), 0, 8, "0.00");
        assertNull(resultado.cancelamento().percentual());
        assertNull(resultado.confirmacao().percentual());
    }

    @Test void consultasSemCapacidadeNaoProduzemOcupacaoInventada() {
        var resultado = CalculadoraIndicadores.calcular(PERIODO, new Contagens(0, 0, 1, 0, 0, 0, 0, 0, 0));
        assertEquals(1, resultado.contagens().totalConsultas());
        assertNull(resultado.ocupacao().percentual());
        conferir(resultado.cancelamento(), 0, 1, "0.00");
    }

    @Test void faltaUsaDesfechosEmVezDeDiluirComConsultasAbertasOuCanceladas() {
        var resultado = CalculadoraIndicadores.calcular(PERIODO, new Contagens(20, 12, 8, 2, 1, 1, 3, 2, 0));
        conferir(resultado.faltas(), 1, 2, "50.00");
        conferir(resultado.cancelamento(), 3, 15, "20.00");
    }

    @Test void confirmacaoRegistradaContinuaContandoDepoisDaConclusao() {
        var resultado = CalculadoraIndicadores.calcular(PERIODO, new Contagens(2, 2, 0, 0, 1, 1, 0, 1, 0));
        assertEquals(0, resultado.contagens().confirmadas());
        conferir(resultado.confirmacao(), 1, 2, "50.00");
    }

    @Test void canceladasContinuamNaPopulacaoDeConfirmacaoMasSaemDaFilaMantida() {
        var resultado = CalculadoraIndicadores.calcular(PERIODO, new Contagens(8, 0, 0, 0, 0, 0, 2, 1, 0));
        conferir(resultado.cancelamento(), 2, 2, "100.00");
        conferir(resultado.confirmacao(), 1, 2, "50.00");
        assertEquals(0, resultado.preenchimentoFila().denominador());
        assertNull(resultado.preenchimentoFila().percentual());
        assertNull(resultado.faltas().percentual());
    }

    @ParameterizedTest
    @CsvSource({"1,3,33.33", "2,3,66.67", "1,32,3.13", "0,4,0.00", "4,4,100.00"})
    void percentuaisTemDuasCasasComArredondamentoHalfUp(long numerador, long denominador, String esperado) {
        conferir(new Taxa(numerador, denominador), numerador, denominador, esperado);
    }

    @Test void multiplicacaoDoPercentualNaoEstouraLongNemPerdePrecisaoPorDouble() {
        conferir(new Taxa(Long.MAX_VALUE, Long.MAX_VALUE), Long.MAX_VALUE, Long.MAX_VALUE, "100.00");
        conferir(new Taxa(1, Long.MAX_VALUE), 1, Long.MAX_VALUE, "0.00");
    }

    @Test void contagensInvalidasSaoRecusadasSemOcultarInconsistencias() {
        assertThrows(IllegalArgumentException.class, () -> new Contagens(-1, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Contagens(1, -1, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Contagens(0, 0, -1, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Contagens(1, 2, 1, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Contagens(1, 1, 1, 0, 0, 0, 0, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> new Contagens(1, 0, 0, 0, 0, 0, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Contagens(1, 1, Long.MAX_VALUE, 1, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Taxa(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Taxa(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Taxa(2, 1));
    }

    @Test void fontesOuPeriodoAusentesNaoViraramContagensVazias() {
        assertThrows(NullPointerException.class, () -> CalculadoraIndicadores.calcular(PERIODO, null));
        assertThrows(NullPointerException.class, () -> CalculadoraIndicadores.calcular(null, new Contagens(0, 0, 0, 0, 0, 0, 0, 0, 0)));
    }

    @Test void diaLocalIncluiAteSemIncluirAMeiaNoiteSeguinte() {
        assertEquals("America/Sao_Paulo", PeriodoIndicadores.FUSO.getId());
        assertEquals(Instant.parse("2026-11-20T03:00:00Z"), PERIODO.inicioInclusivo());
        assertEquals(Instant.parse("2026-11-21T03:00:00Z"), PERIODO.fimExclusivo());
        assertEquals(LocalDate.of(2026, 11, 20), PERIODO.de());
        assertEquals(PERIODO.de(), PERIODO.ate());
    }

    @Test void janelaUsaInicioDeCadaDiaEmVezDeSomarVinteEQuatroHoras() {
        var periodo = PeriodoIndicadores.de("2018-11-04", "2018-11-04");
        assertEquals(Instant.parse("2018-11-04T03:00:00Z"), periodo.inicioInclusivo());
        assertEquals(Instant.parse("2018-11-05T02:00:00Z"), periodo.fimExclusivo());
        assertEquals(Duration.ofHours(23), Duration.between(periodo.inicioInclusivo(), periodo.fimExclusivo()));
    }

    @Test void aceitaSessentaEDoisDiasInclusivosERecusaOSeguinteOuOrdemInvertida() {
        assertEquals(LocalDate.of(2026, 3, 3), PeriodoIndicadores.de("2026-01-01", "2026-03-03").ate());
        assertEquals(400, assertThrows(ApiException.class, () -> PeriodoIndicadores.de("2026-01-01", "2026-03-04")).status());
        assertEquals(400, assertThrows(ApiException.class, () -> PeriodoIndicadores.de("2026-11-21", "2026-11-20")).status());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "2026-2-01", "2026-02-30", "2026-02-29", "2026-11-20T00:00:00Z", "0000-01-01", "+10000-01-01"})
    void recusaDatasAusentesOuInvalidasEmAmbosOsLimites(String valor) {
        assertEquals(400, assertThrows(ApiException.class, () -> PeriodoIndicadores.de(valor, "2026-11-20")).status());
        assertEquals(400, assertThrows(ApiException.class, () -> PeriodoIndicadores.de("2026-11-20", valor)).status());
    }

    @Test void construtorDiretoTambemValidaJanelaENaoEstouraConversaoDeData() {
        assertEquals(400, assertThrows(ApiException.class, () -> new PeriodoIndicadores(null, PERIODO.ate())).status());
        assertEquals(400, assertThrows(ApiException.class, () -> new PeriodoIndicadores(PERIODO.de(), null)).status());
        assertEquals(400, assertThrows(ApiException.class, () -> new PeriodoIndicadores(LocalDate.MIN, LocalDate.MAX)).status());
        assertDoesNotThrow(() -> PeriodoIndicadores.de("2024-02-29", "2024-02-29"));
    }

    private static void conferir(Taxa taxa, long numerador, long denominador, String percentual) {
        assertEquals(numerador, taxa.numerador());
        assertEquals(denominador, taxa.denominador());
        assertEquals(new BigDecimal(percentual), taxa.percentual());
    }
}
