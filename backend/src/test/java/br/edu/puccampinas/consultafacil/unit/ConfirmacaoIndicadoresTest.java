/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

package br.edu.puccampinas.consultafacil.unit;

import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.domain.enums.StatusConsulta;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import br.edu.puccampinas.consultafacil.service.*;
import br.edu.puccampinas.consultafacil.support.ConsultasEmMemoria;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

// Os eventos podem estar repetidos ou fora de ordem; a contagem deve continuar
// vinculada às consultas distintas do período.
class ConfirmacaoIndicadoresTest {
    private static final ObjectId MEDICO = new ObjectId(), OUTRO_MEDICO = new ObjectId();
    private static final Instant INICIO = Instant.parse("2026-11-20T12:00:00Z");
    private static final PeriodoIndicadores PERIODO = PeriodoIndicadores.de("2026-11-20", "2026-11-20");
    private final ConsultasEmMemoria consultas = new ConsultasEmMemoria();
    private final Map<ObjectId, Document> historico = new LinkedHashMap<>();
    private boolean indisponivel;
    private final HistoricoProcessor processor = new HistoricoProcessor(consultas, evento -> {
        if (indisponivel) throw new IllegalStateException("Histórico temporariamente indisponível");
        historico.putIfAbsent(evento.getObjectId("_id"), Document.parse(evento.toJson()));
    });
    private final Clock relogio = Clock.fixed(Instant.parse("2026-11-22T15:00:00Z"), ZoneOffset.UTC);
    private final UsuarioLogado secretaria = new UsuarioLogado(new ObjectId().toHexString(), "Secretaria", Perfil.SECRETARIA_ADMIN);
    private final RegistroConsultaService registro = new RegistroConsultaService(consultas, processor, relogio);
    private final StatusConsultaService status = new StatusConsultaService(consultas,
            new AcessoConsulta(login -> Optional.empty(), login -> Optional.empty()), registro, relogio);

    private ObjectId criar(ObjectId medico, Instant inicio) {
        return registro.criar(new Document("pacienteId", new ObjectId()).append("medicoId", medico)
                .append("especialidadeId", new ObjectId()).append("inicio", Date.from(inicio))
                .append("fim", Date.from(inicio.plusSeconds(1800))), secretaria).getObjectId("_id");
    }

    private ObjectId criar() { return criar(MEDICO, INICIO); }
    private void confirmar(ObjectId id) { status.alterar(id, StatusConsulta.CONFIRMADA, secretaria); }
    private List<Document> fonteConsultas() { return new ArrayList<>(consultas.docs.values()); }
    private List<Document> fonteHistorico() { return new ArrayList<>(historico.values()); }
    private CalculadoraConfirmacao.Resultado calcular() {
        return CalculadoraConfirmacao.calcular(PERIODO, Optional.of(MEDICO), fonteConsultas(), fonteHistorico());
    }

    @Test void fixtureManualContaConfirmacaoAposConclusaoOuCancelamento() {
        criar();
        confirmar(criar());
        for (int i = 0; i < 2; i++) {
            ObjectId id = criar();
            confirmar(id);
            status.alterar(id, StatusConsulta.CONCLUIDA, secretaria);
        }
        status.alterar(criar(), StatusConsulta.FALTA, secretaria);
        ObjectId cancelada = criar();
        confirmar(cancelada);
        registro.alterar(cancelada, new Document("status", "CANCELADA"), secretaria);

        var resultado = calcular();
        assertEquals(6, resultado.totalConsultas());
        assertEquals(4, resultado.comConfirmacaoRegistrada());
        assertEquals(0, resultado.consultasComProjecaoPendente());
        assertEquals(0, resultado.consultasSemBaseHistorica());
        assertEquals(new BigDecimal("66.67"), resultado.confirmacao().orElseThrow().percentual());
        var indicadores = CalculadoraIndicadores.calcular(PERIODO, new CalculadoraIndicadores.Contagens(
                8, 6, 1, 1, 2, 1, 1, resultado.comConfirmacaoRegistrada(), 2));
        assertEquals(indicadores.confirmacao(), resultado.confirmacao().orElseThrow());
        assertTrue(historico.values().stream().allMatch(e -> e.getDate("ocorridoEm").toInstant().isAfter(PERIODO.fimExclusivo())));
    }

    @Test void eventosRepetidosForaDeOrdemNaoMultiplicamConsultasConfirmadas() {
        ObjectId id = criar();
        confirmar(id);
        var eventos = fonteHistorico();
        Document confirmacao = eventos.stream().filter(e -> "CONFIRMACAO".equals(e.getString("evento"))).findFirst().orElseThrow();
        eventos.add(confirmacao);
        eventos.add(new Document(confirmacao).append("_id", new ObjectId()).append("sequencia", 99));
        java.util.Collections.reverse(eventos);
        var resultado = CalculadoraConfirmacao.calcular(PERIODO, Optional.of(MEDICO), fonteConsultas(), eventos);
        assertEquals(1, resultado.totalConsultas());
        assertEquals(1, resultado.comConfirmacaoRegistrada());
        assertEquals(new BigDecimal("100.00"), resultado.confirmacao().orElseThrow().percentual());
    }

    @Test void medicoEPeriodoSaoOsDaConsultaNaoOsDoEvento() {
        ObjectId transferida = criar(OUTRO_MEDICO, Instant.parse("2026-11-19T12:00:00Z"));
        confirmar(transferida);
        registro.alterar(transferida, new Document("medicoId", MEDICO).append("inicio", Date.from(INICIO))
                .append("fim", Date.from(INICIO.plusSeconds(1800))), secretaria);
        ObjectId outra = criar(OUTRO_MEDICO, INICIO);
        var resultado = calcular();
        assertEquals(1, resultado.totalConsultas());
        assertEquals(1, resultado.comConfirmacaoRegistrada());
        var outro = CalculadoraConfirmacao.calcular(PERIODO, Optional.of(OUTRO_MEDICO), fonteConsultas(), fonteHistorico());
        assertEquals(1, outro.totalConsultas());
        assertEquals(0, outro.comConfirmacaoRegistrada());
        assertEquals(new BigDecimal("0.00"), outro.confirmacao().orElseThrow().percentual());
        assertNotEquals(transferida, outra);
        var todos = CalculadoraConfirmacao.calcular(PERIODO, Optional.empty(), fonteConsultas(), fonteHistorico());
        assertEquals(2, todos.totalConsultas());
        assertEquals(new BigDecimal("50.00"), todos.confirmacao().orElseThrow().percentual());
    }

    @Test void consultaForaDaJanelaNaoEntraMesmoComEventoDentroDela() {
        confirmar(criar(MEDICO, PERIODO.inicioInclusivo().minusSeconds(1)));
        confirmar(criar(MEDICO, PERIODO.inicioInclusivo()));
        confirmar(criar(MEDICO, PERIODO.fimExclusivo().minusSeconds(1)));
        confirmar(criar(MEDICO, PERIODO.fimExclusivo()));
        fonteHistorico().forEach(e -> e.put("ocorridoEm", Date.from(INICIO)));
        var resultado = calcular();
        assertEquals(2, resultado.totalConsultas());
        assertEquals(2, resultado.comConfirmacaoRegistrada());
        assertEquals(new BigDecimal("100.00"), resultado.confirmacao().orElseThrow().percentual());
    }

    @Test void periodoVazioNaoViraTaxaZeroOuConfirmacaoDeOutraConsulta() {
        confirmar(criar(OUTRO_MEDICO, INICIO));
        var resultado = calcular();
        assertEquals(0, resultado.totalConsultas());
        assertEquals(0, resultado.comConfirmacaoRegistrada());
        assertFalse(resultado.atualizacaoPendente());
        assertEquals(0, resultado.confirmacao().orElseThrow().denominador());
        assertNull(resultado.confirmacao().orElseThrow().percentual());
    }

    @Test void naoTruncaEmCemConsultasNemUsaAPrimeiraPaginaDaTimeline() {
        for (int i = 0; i < 101; i++) confirmar(criar());
        assertEquals(202, historico.size());
        var resultado = calcular();
        assertEquals(101, resultado.totalConsultas());
        assertEquals(101, resultado.comConfirmacaoRegistrada());
        assertEquals(new BigDecimal("100.00"), resultado.confirmacao().orElseThrow().percentual());
    }

    @Test void pendenciaNaoEhProcessadaNemContadaComoConfirmacaoProjetada() {
        indisponivel = true;
        ObjectId id = criar();
        confirmar(id);
        Document antes = consultas.buscar(id).orElseThrow();
        var resultado = calcular();
        assertEquals("CONFIRMADA", antes.getString("status"));
        assertEquals(1, resultado.totalConsultas());
        assertEquals(0, resultado.comConfirmacaoRegistrada());
        assertEquals(1, resultado.consultasComProjecaoPendente());
        assertEquals(1, resultado.consultasSemBaseHistorica());
        assertTrue(resultado.atualizacaoPendente());
        assertTrue(resultado.confirmacao().isEmpty());
        assertEquals(antes, consultas.buscar(id).orElseThrow());
        assertTrue(historico.isEmpty());

        indisponivel = false;
        processor.processarConsulta(id);
        var atualizada = calcular();
        assertFalse(atualizada.atualizacaoPendente());
        assertEquals(0, atualizada.consultasSemBaseHistorica());
        assertEquals(1, atualizada.comConfirmacaoRegistrada());
        assertEquals(new BigDecimal("100.00"), atualizada.confirmacao().orElseThrow().percentual());
    }

    @Test void pendenciaDeOutroMedicoNaoBloqueiaATaxaDoEscopoSelecionado() {
        confirmar(criar());
        indisponivel = true;
        confirmar(criar(OUTRO_MEDICO, INICIO));
        assertFalse(calcular().atualizacaoPendente());
        assertEquals(new BigDecimal("100.00"), calcular().confirmacao().orElseThrow().percentual());
        var todos = CalculadoraConfirmacao.calcular(PERIODO, Optional.empty(), fonteConsultas(), fonteHistorico());
        assertTrue(todos.atualizacaoPendente());
        assertTrue(todos.confirmacao().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AGENDADA", "CONFIRMADA", "CONCLUIDA", "FALTA", "CANCELADA"})
    void legadoSemBaseHistoricaNaoInventaConfirmacaoPeloStatus(String atual) {
        ObjectId id = criar();
        historico.clear();
        consultas.docs.get(id).put("status", atual);
        var resultado = calcular();
        assertEquals(1, resultado.totalConsultas());
        assertEquals(0, resultado.comConfirmacaoRegistrada());
        assertEquals(1, resultado.consultasSemBaseHistorica());
        assertFalse(resultado.atualizacaoPendente());
        assertTrue(resultado.confirmacao().isEmpty());
    }

    @Test void confirmacaoComprovadaEmLegadoDispensaInventarEventoDeCriacao() {
        ObjectId id = criar();
        confirmar(id);
        historico.values().removeIf(e -> "CRIACAO".equals(e.getString("evento")));
        var resultado = calcular();
        assertEquals(1, resultado.comConfirmacaoRegistrada());
        assertEquals(0, resultado.consultasSemBaseHistorica());
        assertEquals(new BigDecimal("100.00"), resultado.confirmacao().orElseThrow().percentual());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMADA", "CONCLUIDA"})
    void criacaoProjetadaNaoTornaZeroVerdadeiroUmaConfirmacaoAusenteDoHistorico(String atual) {
        ObjectId id = criar();
        consultas.docs.get(id).put("status", atual);
        var resultado = calcular();
        assertEquals(0, resultado.comConfirmacaoRegistrada());
        assertEquals(1, resultado.consultasSemBaseHistorica());
        assertFalse(resultado.atualizacaoPendente());
        assertTrue(resultado.confirmacao().isEmpty());
        assertEquals(1, historico.size());
    }

    @Test void outrosTiposDeEventoNaoSaoProvaDeConfirmacao() {
        ObjectId id = criar();
        status.alterar(id, StatusConsulta.FALTA, secretaria);
        var resultado = calcular();
        assertEquals(0, resultado.comConfirmacaoRegistrada());
        assertEquals(new BigDecimal("0.00"), resultado.confirmacao().orElseThrow().percentual());
        historico.values().removeIf(e -> "CRIACAO".equals(e.getString("evento")));
        assertTrue(calcular().confirmacao().isEmpty());
    }

    @Test void documentosRepetidosNaoDuplicamDenominadorESnapshotsDivergentesSaoRecusados() {
        ObjectId id = criar();
        confirmar(id);
        var repetidos = List.of(consultas.docs.get(id), new Document(consultas.docs.get(id)));
        var resultado = CalculadoraConfirmacao.calcular(PERIODO, Optional.of(MEDICO), repetidos, fonteHistorico());
        assertEquals(1, resultado.totalConsultas());
        assertEquals(1, resultado.comConfirmacaoRegistrada());
        var divergentes = List.of(consultas.docs.get(id), new Document(consultas.docs.get(id)).append("status", "CANCELADA"));
        assertThrows(IllegalArgumentException.class, () -> CalculadoraConfirmacao.calcular(PERIODO, Optional.of(MEDICO), divergentes, fonteHistorico()));
    }

    @Test void leituraNaoAlteraFontesNemSuasPendenciasOuEventos() {
        confirmar(criar());
        var fontes = new Document("consultas", fonteConsultas()).append("historico", fonteHistorico());
        String antes = fontes.toJson();
        calcular();
        assertEquals(antes, fontes.toJson());
    }

    @Test void fontesAusentesOuReferenciasInvalidasNaoViramIndicadoresZero() {
        assertThrows(NullPointerException.class, () -> CalculadoraConfirmacao.calcular(null, Optional.empty(), List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> CalculadoraConfirmacao.calcular(PERIODO, null, List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> CalculadoraConfirmacao.calcular(PERIODO, Optional.empty(), null, List.of()));
        assertThrows(NullPointerException.class, () -> CalculadoraConfirmacao.calcular(PERIODO, Optional.empty(), List.of(), null));
        ObjectId id = criar();
        var semIdentidade = new Document(consultas.docs.get(id));
        semIdentidade.remove("_id");
        assertThrows(NullPointerException.class, () -> CalculadoraConfirmacao.calcular(PERIODO, Optional.empty(), List.of(semIdentidade), fonteHistorico()));
        var referenciaTextual = new Document("evento", "CONFIRMACAO").append("consultaId", id.toHexString());
        assertThrows(ClassCastException.class, () -> CalculadoraConfirmacao.calcular(PERIODO, Optional.empty(), fonteConsultas(), List.of(referenciaTextual)));
    }
}
