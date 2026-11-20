package br.edu.puccampinas.consultafacil.unit;

import br.edu.puccampinas.consultafacil.service.CalculadoraIndicadores;
import br.edu.puccampinas.consultafacil.service.CalculadoraPreenchimentoFila;
import br.edu.puccampinas.consultafacil.service.PeriodoIndicadores;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PreenchimentoFilaIndicadoresTest {
    private static final ObjectId MEDICO = new ObjectId(), OUTRO_MEDICO = new ObjectId();
    private static final Instant INICIO = Instant.parse("2026-11-20T12:00:00Z");
    private static final PeriodoIndicadores PERIODO = PeriodoIndicadores.de("2026-11-20", "2026-11-20");

    private static Document consulta(String status) {
        return new Document("_id", new ObjectId()).append("pacienteId", new ObjectId())
                .append("medicoId", MEDICO).append("especialidadeId", new ObjectId())
                .append("inicio", Date.from(INICIO)).append("status", status);
    }

    private static Document atendida(Document consulta) {
        return new Document("_id", new ObjectId()).append("status", "ATENDIDA")
                .append("pacienteId", consulta.getObjectId("pacienteId"))
                .append("especialidadeId", consulta.getObjectId("especialidadeId"))
                .append("medicoId", null).append("consultaAgendadaId", consulta.getObjectId("_id"))
                .append("ultimaSinalizacao", null).append("atendidaEm", Date.from(Instant.parse("2026-11-19T12:00:00Z")));
    }

    private static CalculadoraPreenchimentoFila.Resultado calcular(List<Document> consultas, List<Document> fila) {
        return CalculadoraPreenchimentoFila.calcular(PERIODO, Optional.of(MEDICO), consultas, fila);
    }

    @Test void fixtureManualContaDuasReservasDaFilaEntreCincoMantidas() {
        var agendada = consulta("AGENDADA");
        var confirmada = consulta("CONFIRMADA");
        var concluida = consulta("CONCLUIDA");
        var outraConcluida = consulta("CONCLUIDA");
        var falta = consulta("FALTA");
        var cancelada = consulta("CANCELADA");
        var resultado = calcular(List.of(agendada, confirmada, concluida, outraConcluida, falta, cancelada),
                List.of(atendida(agendada), atendida(concluida), atendida(cancelada)));
        assertEquals(5, resultado.consultasNaoCanceladas());
        assertEquals(2, resultado.preenchidasPelaFila());
        assertEquals(0, resultado.inscricoesComVinculoInvalido());
        assertEquals(new BigDecimal("40.00"), resultado.preenchimentoFila().orElseThrow().percentual());
        var indicadores = CalculadoraIndicadores.calcular(PERIODO,
                new CalculadoraIndicadores.Contagens(8, 6, 1, 1, 2, 1, 1, 4, resultado.preenchidasPelaFila()));
        assertEquals(indicadores.preenchimentoFila(), resultado.preenchimentoFila().orElseThrow());
    }

    @Test void variasInscricoesOuDocumentosRepetidosContamUmaConsultaSo() {
        var consulta = consulta("CONFIRMADA");
        var primeira = atendida(consulta);
        var segunda = atendida(consulta);
        var resultado = calcular(List.of(consulta, new Document(consulta)),
                List.of(primeira, new Document(primeira), segunda));
        assertEquals(1, resultado.consultasNaoCanceladas());
        assertEquals(1, resultado.preenchidasPelaFila());
        assertEquals(new BigDecimal("100.00"), resultado.preenchimentoFila().orElseThrow().percentual());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AGENDADA", "CONFIRMADA", "CONCLUIDA", "FALTA"})
    void todosOsStatusNaoCanceladosMantemAOrigemDaFila(String status) {
        var consulta = consulta(status);
        var resultado = calcular(List.of(consulta), List.of(atendida(consulta)));
        assertEquals(1, resultado.consultasNaoCanceladas());
        assertEquals(1, resultado.preenchidasPelaFila());
    }

    @Test void snapshotDepoisDoCancelamentoSaiDeAmbosOsLadosSemAlterarAInscricao() {
        var consulta = consulta("AGENDADA");
        var inscricao = atendida(consulta);
        assertEquals(1, calcular(List.of(consulta), List.of(inscricao)).preenchidasPelaFila());
        var cancelada = new Document(consulta).append("status", "CANCELADA");
        var resultado = calcular(List.of(cancelada), List.of(inscricao));
        assertEquals(0, resultado.consultasNaoCanceladas());
        assertEquals(0, resultado.preenchidasPelaFila());
        assertEquals(0, resultado.inscricoesComVinculoInvalido());
        assertNull(resultado.preenchimentoFila().orElseThrow().percentual());
        assertEquals("ATENDIDA", inscricao.getString("status"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AGUARDANDO", "SINALIZADA", "CANCELADA", "EXPIRADA"})
    void inscricaoSemAtendimentoNaoComprovaPreenchimento(String status) {
        var consulta = consulta("AGENDADA");
        var inscricao = atendida(consulta).append("status", status).append("consultaAgendadaId", null).append("atendidaEm", null);
        if ("SINALIZADA".equals(status)) inscricao.append("ultimaSinalizacao", new Document("dataHora", Date.from(INICIO))
                .append("medicoId", MEDICO).append("notificacaoId", new ObjectId()));
        var resultado = calcular(List.of(consulta), List.of(inscricao));
        assertEquals(1, resultado.consultasNaoCanceladas());
        assertEquals(0, resultado.preenchidasPelaFila());
        assertEquals(new BigDecimal("0.00"), resultado.preenchimentoFila().orElseThrow().percentual());
        assertEquals(status, inscricao.getString("status"));
    }

    @Test void dataDoAtendimentoEMedicoPreferidoNaoRedefinemRecorteDaConsulta() {
        var propria = consulta("CONCLUIDA");
        var outra = consulta("AGENDADA").append("medicoId", OUTRO_MEDICO);
        var filaPropria = atendida(propria).append("medicoId", OUTRO_MEDICO);
        var filaOutra = atendida(outra).append("medicoId", MEDICO).append("atendidaEm", Date.from(INICIO));
        var consultas = List.of(propria, outra);
        var fila = List.of(filaPropria, filaOutra);
        var resultado = calcular(consultas, fila);
        assertEquals(1, resultado.consultasNaoCanceladas());
        assertEquals(1, resultado.preenchidasPelaFila());
        assertEquals(0, resultado.inscricoesComVinculoInvalido());
        var todos = CalculadoraPreenchimentoFila.calcular(PERIODO, Optional.empty(), consultas, fila);
        assertEquals(2, todos.consultasNaoCanceladas());
        assertEquals(2, todos.preenchidasPelaFila());
    }

    @Test void janelaIncluiInicioEExcluiMeiaNoiteSeguinteMesmoComAtendidaEmNoPeriodo() {
        var antes = consulta("AGENDADA").append("inicio", Date.from(PERIODO.inicioInclusivo().minusMillis(1)));
        var primeira = consulta("AGENDADA").append("inicio", Date.from(PERIODO.inicioInclusivo()));
        var ultima = consulta("FALTA").append("inicio", Date.from(PERIODO.fimExclusivo().minusMillis(1)));
        var depois = consulta("AGENDADA").append("inicio", Date.from(PERIODO.fimExclusivo()));
        var consultas = List.of(antes, primeira, ultima, depois);
        var fila = consultas.stream().map(c -> atendida(c).append("atendidaEm", Date.from(INICIO))).toList();
        var resultado = calcular(consultas, fila);
        assertEquals(2, resultado.consultasNaoCanceladas());
        assertEquals(2, resultado.preenchidasPelaFila());
        assertEquals(0, resultado.inscricoesComVinculoInvalido());
    }

    @Test void periodoVazioOuVinculosResolvidosForaDoRecorteNaoViraramZeroPorCento() {
        var consulta = consulta("AGENDADA").append("inicio", Date.from(PERIODO.fimExclusivo()));
        for (var resultado : List.of(calcular(List.of(), List.of()), calcular(List.of(consulta), List.of(atendida(consulta))))) {
            assertEquals(0, resultado.consultasNaoCanceladas());
            assertEquals(0, resultado.preenchidasPelaFila());
            assertEquals(0, resultado.inscricoesComVinculoInvalido());
            assertNull(resultado.preenchimentoFila().orElseThrow().percentual());
        }
    }

    @Test void fontesComMaisDeCemConsultasEInscricoesNaoSaoTruncadas() {
        List<Document> consultas = new ArrayList<>(), fila = new ArrayList<>();
        for (int i = 0; i < 121; i++) {
            var consulta = consulta("AGENDADA");
            consultas.add(consulta);
            fila.add(atendida(consulta));
        }
        var resultado = calcular(consultas, fila);
        assertEquals(121, resultado.consultasNaoCanceladas());
        assertEquals(121, resultado.preenchidasPelaFila());
        assertEquals(new BigDecimal("100.00"), resultado.preenchimentoFila().orElseThrow().percentual());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"inválido", "000000000000000000000001"})
    void referenciaNulaOuTextualNaoEhConvertidaEmPreenchimentoOuZeroConfiavel(String referencia) {
        var consulta = consulta("AGENDADA");
        var fila = atendida(consulta).append("consultaAgendadaId", referencia);
        var resultado = calcular(List.of(consulta), List.of(fila));
        assertEquals(1, resultado.consultasNaoCanceladas());
        assertEquals(0, resultado.preenchidasPelaFila());
        assertEquals(1, resultado.inscricoesComVinculoInvalido());
        assertTrue(resultado.preenchimentoFila().isEmpty());
    }

    @Test void vinculoOrfaoOuAusenteSuspendeTaxaMesmoComOutraReservaComprovada() {
        var consulta = consulta("AGENDADA");
        var conhecida = atendida(consulta);
        var orfa = atendida(consulta).append("consultaAgendadaId", new ObjectId());
        var semVinculo = atendida(consulta);
        semVinculo.remove("consultaAgendadaId");
        var resultado = calcular(List.of(consulta), List.of(conhecida, orfa, new Document(orfa), semVinculo));
        assertEquals(1, resultado.consultasNaoCanceladas());
        assertEquals(1, resultado.preenchidasPelaFila());
        assertEquals(2, resultado.inscricoesComVinculoInvalido());
        assertTrue(resultado.preenchimentoFila().isEmpty());
    }

    @Test void snapshotsDivergentesDaMesmaConsultaOuInscricaoSaoRecusados() {
        var consulta = consulta("AGENDADA");
        var fila = atendida(consulta);
        assertThrows(IllegalArgumentException.class, () -> calcular(
                List.of(consulta, new Document(consulta).append("status", "CANCELADA")), List.of(fila)));
        assertThrows(IllegalArgumentException.class, () -> calcular(List.of(consulta),
                List.of(fila, new Document(fila).append("consultaAgendadaId", new ObjectId()))));
    }

    @Test void calculoNaoExpiraRevisaOuModificaFontes() {
        var consulta = consulta("CONFIRMADA");
        var inscricao = atendida(consulta);
        var sinalizada = atendida(consulta).append("status", "SINALIZADA").append("consultaAgendadaId", null)
                .append("atendidaEm", null).append("validoAte", Date.from(Instant.parse("2026-01-01T00:00:00Z")))
                .append("ultimaSinalizacao", new Document("dataHora", Date.from(INICIO)));
        var consultas = List.of(consulta);
        var fila = List.of(inscricao, sinalizada);
        var fontes = new Document("consultas", consultas).append("fila", fila);
        String antes = fontes.toJson();
        calcular(consultas, fila);
        assertEquals(antes, fontes.toJson());
        assertEquals("SINALIZADA", sinalizada.getString("status"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "DESCONHECIDO"})
    void statusDeInscricaoAusenteOuDesconhecidoNaoViraZero(String status) {
        var consulta = consulta("AGENDADA");
        assertThrows(IllegalArgumentException.class, () -> calcular(List.of(consulta), List.of(atendida(consulta).append("status", status))));
    }

    @Test void fontesAusentesOuDocumentosSemIdentidadeNaoViraramListasVazias() {
        assertThrows(NullPointerException.class, () -> CalculadoraPreenchimentoFila.calcular(null, Optional.empty(), List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> CalculadoraPreenchimentoFila.calcular(PERIODO, null, List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> CalculadoraPreenchimentoFila.calcular(PERIODO, Optional.empty(), null, List.of()));
        assertThrows(NullPointerException.class, () -> CalculadoraPreenchimentoFila.calcular(PERIODO, Optional.empty(), List.of(), null));
        var consulta = consulta("AGENDADA");
        var semIdentidade = atendida(consulta);
        semIdentidade.remove("_id");
        assertThrows(NullPointerException.class, () -> calcular(List.of(consulta), List.of(semIdentidade)));
        var consultaSemIdentidade = new Document(consulta);
        consultaSemIdentidade.remove("_id");
        assertThrows(NullPointerException.class, () -> calcular(List.of(consultaSemIdentidade), List.of()));
    }
}
