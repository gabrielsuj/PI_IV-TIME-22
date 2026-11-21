package br.edu.puccampinas.consultafacil.unit;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.repository.IndicadoresMongoRepository;
import br.edu.puccampinas.consultafacil.service.PeriodoIndicadores;
import br.edu.puccampinas.consultafacil.support.FontesIndicadoresEmMemoria;
import com.mongodb.MongoException;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.*;

import static br.edu.puccampinas.consultafacil.support.FontesIndicadoresEmMemoria.*;
import static org.junit.jupiter.api.Assertions.*;

class IndicadoresMongoRepositoryTest {
    @ParameterizedTest
    @ValueSource(strings = {"medicos", "novaConsulta", "remarcacao", "versao", "pendencias", "agenda", "bloqueio", "ocupante", "historico", "fila", "vinculo"})
    void mudancaEntreFontesRecusaRespostaInteiraSemRepetirCalculoOuEscrita(String fonte) {
        var f = new FontesComConferencia();
        var fora = new Document(f.consultas.getFirst()).append("_id", new ObjectId())
                .append("inicio", Date.from(PERIODO.fimExclusivo())).append("fim", Date.from(PERIODO.fimExclusivo().plusSeconds(1800)));
        f.consultas.add(fora);
        f.fila.add(atendida(fora.getObjectId("_id")));
        f.aoConferir = () -> {
            switch (fonte) {
                case "medicos" -> f.medicos.add(new ObjectId());
                case "novaConsulta" -> f.consultas.add(new Document(f.consultas.getFirst()).append("_id", new ObjectId()));
                case "remarcacao" -> f.consultas.getFirst().put("inicio", Date.from(PERIODO.fimExclusivo()));
                case "versao" -> f.consultas.getFirst().put("versao", 2);
                case "pendencias" -> f.consultas.getFirst().put("eventosPendentes", List.of(new Document("_id", new ObjectId())));
                case "agenda" -> f.agendas.getFirst().put("duracaoMinutos", 60);
                case "bloqueio" -> f.bloqueios.get(MEDICO).getFirst().getDate("fim").setTime(PERIODO.fimExclusivo().toEpochMilli());
                case "ocupante" -> f.consultas.getFirst().put("fim", Date.from(PERIODO.fimExclusivo()));
                case "historico" -> f.eventos.removeFirst();
                case "fila" -> f.fila.getFirst().put("consultaAgendadaId", f.consultas.get(1).getObjectId("_id"));
                case "vinculo" -> f.consultas.remove(fora);
                default -> throw new AssertionError(fonte);
            }
        };
        var erro = assertThrows(ApiException.class, () -> new IndicadoresMongoRepository(f).ler(PERIODO, Optional.empty()));
        assertEquals(503, erro.status());
        assertTrue(erro.getMessage().contains("mudaram"));
        assertEquals(2, Collections.frequency(f.chamadas, "medicos"));
        assertEquals(Set.of("fila", "vinculo").contains(fonte) ? 2 : 1, Collections.frequency(f.chamadas, "fila"));
    }

    @Test void ordemDasFontesNaoImportaEConferenciaTemQuantidadeLimitadaDeLeituras() {
        var f = new FontesComConferencia();
        f.aoConferir = () -> {
            Collections.reverse(f.medicos); Collections.reverse(f.consultas);
            Collections.reverse(f.agendas); Collections.reverse(f.eventos); Collections.reverse(f.fila);
        };
        var r = new IndicadoresMongoRepository(f).ler(PERIODO, Optional.empty());
        assertEquals(2, r.fila().preenchidas());
        assertEquals(4, r.confirmacao().registradas());
        for (String fonte : List.of("medicos", "consultas", "agendas", "historico", "fila"))
            assertEquals(2, Collections.frequency(f.chamadas, fonte), fonte);
        assertEquals(4, f.janelas.size());
    }

    @Test void medicoScopadoInicialmenteVazioDetectaConsultaCriadaDuranteLeitura() {
        var f = new FontesComConferencia();
        var nova = f.consultas.getFirst();
        f.consultas.clear(); f.eventos.clear(); f.fila.clear();
        f.aoConferir = () -> f.consultas.add(nova);
        assertEquals(503, assertThrows(ApiException.class,
                () -> new IndicadoresMongoRepository(f).ler(PERIODO, Optional.of(MEDICO))).status());
    }

    @Test void referenciaInicialmenteAusenteQueApareceNaConferenciaInvalidaOResumo() {
        var f = new FontesComConferencia();
        var fora = new Document(f.consultas.getFirst()).append("_id", new ObjectId())
                .append("inicio", Date.from(PERIODO.fimExclusivo())).append("fim", Date.from(PERIODO.fimExclusivo().plusSeconds(1800)));
        f.fila.add(atendida(fora.getObjectId("_id")));
        f.aoConferir = () -> f.consultas.add(fora);
        var erro = assertThrows(ApiException.class, () -> new IndicadoresMongoRepository(f).ler(PERIODO, Optional.empty()));
        assertEquals(503, erro.status());
        assertEquals(2, Collections.frequency(f.chamadas, "vinculadas"));
    }

    @Test void falhaMongoDuranteConferenciaNaoEntregaOResumoJaCalculado() {
        var f = new FontesComConferencia();
        f.aoConferir = () -> { throw new MongoException("erro interno na conferência"); };
        var erro = assertThrows(ApiException.class, () -> new IndicadoresMongoRepository(f).ler(PERIODO, Optional.empty()));
        assertEquals(503, erro.status());
        assertFalse(erro.getMessage().contains("erro interno"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"medicoAusente", "statusDesconhecido", "duplicataDivergente", "duasAgendas", "inicioAusente", "pendenciasInvalidas"})
    void fontesEstruturalmenteInconsistentesNaoViramZeroNemErroInternoExposto(String defeito) {
        var f = fixture();
        switch (defeito) {
            case "medicoAusente" -> f.medicos.clear();
            case "statusDesconhecido" -> f.consultas.getFirst().put("status", "DESCONHECIDO");
            case "duplicataDivergente" -> f.consultas.add(new Document(f.consultas.getFirst()).append("status", "CANCELADA"));
            case "duasAgendas" -> f.agendas.add(new Document(f.agendas.getFirst()));
            case "inicioAusente" -> f.consultas.getFirst().remove("inicio");
            case "pendenciasInvalidas" -> f.consultas.getFirst().put("eventosPendentes", "inválido");
            default -> throw new AssertionError(defeito);
        }
        var erro = assertThrows(ApiException.class, () -> new IndicadoresMongoRepository(f).ler(PERIODO, Optional.empty()));
        assertEquals(503, erro.status());
        assertFalse(erro.getMessage().contains("DESCONHECIDO"));
        assertFalse(erro.getMessage().contains(MEDICO.toHexString()));
    }

    private static final class FontesComConferencia extends FontesIndicadoresEmMemoria {
        Runnable aoConferir = () -> {};
        int leiturasMedicos;
        FontesComConferencia() {
            var f = fixture();
            medicos.addAll(f.medicos); consultas.addAll(f.consultas); agendas.addAll(f.agendas);
            eventos.addAll(f.eventos); fila.addAll(f.fila); bloqueios.putAll(f.bloqueios);
        }
        @Override public List<ObjectId> medicos(Optional<ObjectId> medico) {
            if (++leiturasMedicos == 2) aoConferir.run();
            return super.medicos(medico);
        }
        @Override public List<Document> consultas(PeriodoIndicadores periodo, Optional<ObjectId> medico) {
            return super.consultas(periodo, medico).stream()
                    .filter(d -> !d.getDate("inicio").toInstant().isBefore(periodo.inicioInclusivo())
                            && d.getDate("inicio").toInstant().isBefore(periodo.fimExclusivo())
                            && (medico.isEmpty() || medico.get().equals(d.getObjectId("medicoId"))))
                    .map(d -> { var projecao = new Document(d); projecao.remove("fim"); return projecao; }).toList();
        }
        @Override public List<Document> ocupantes(ObjectId medico, Instant inicio, Instant fim) {
            return super.ocupantes(medico, inicio, fim).stream()
                    .filter(d -> d.getDate("inicio").toInstant().isBefore(fim) && d.getDate("fim").toInstant().isAfter(inicio)
                            && !"CANCELADA".equals(d.getString("status"))).toList();
        }
    }

    @Test void fixtureManualConsolidaTodasAsFontesSemMudarDados() {
        var f = fixture();
        String antes = new Document("consultas", f.consultas).append("agendas", f.agendas).append("eventos", f.eventos).append("fila", f.fila).toJson();
        var r = new IndicadoresMongoRepository(f).ler(PERIODO, Optional.empty());
        assertEquals(1, r.consultas().agendadas());
        assertEquals(1, r.consultas().confirmadas());
        assertEquals(2, r.consultas().concluidas());
        assertEquals(1, r.consultas().faltas());
        assertEquals(1, r.consultas().canceladas());
        assertEquals(8, r.capacidade().total());
        assertEquals(6, r.capacidade().ocupadas());
        assertEquals(4, r.confirmacao().registradas());
        assertEquals(2, r.fila().preenchidas());
        assertEquals(0, r.fila().inscricoesComVinculoInvalido());
        assertEquals(antes, new Document("consultas", f.consultas).append("agendas", f.agendas).append("eventos", f.eventos).append("fila", f.fila).toJson());
    }

    @Test void medicoSelecionadoNaoRecebeDadosOuAnomaliasNaoAtribuiveisDeOutroMedico() {
        var f = fixture();
        ObjectId outro = new ObjectId();
        f.medicos.add(outro);
        var c = new Document(f.consultas.getFirst()).append("_id", new ObjectId()).append("medicoId", outro);
        f.consultas.add(c);
        f.fila.add(atendida(c.getObjectId("_id")));
        f.fila.add(atendida(new ObjectId()));
        var r = new IndicadoresMongoRepository(f).ler(PERIODO, Optional.of(MEDICO));
        assertEquals(1, r.consultas().agendadas());
        assertEquals(2, r.fila().preenchidas());
        assertEquals(0, r.fila().inscricoesComVinculoInvalido());
        assertEquals(0, r.capacidade().medicosSemAgenda());
        assertTrue(f.janelas.stream().allMatch(j -> MEDICO.equals(j.medico())));
        var global = new IndicadoresMongoRepository(f).ler(PERIODO, Optional.empty());
        assertEquals(2, global.consultas().agendadas());
        assertEquals(1, global.fila().inscricoesComVinculoInvalido());
        assertEquals(1, global.capacidade().medicosSemAgenda());
    }

    @Test void filaGlobalResolveVinculoForaDoPeriodoEmVezDeClassificarComoOrfao() {
        var f = fixture();
        var fora = new Document(f.consultas.getFirst()).append("_id", new ObjectId())
                .append("inicio", Date.from(PERIODO.fimExclusivo())).append("fim", Date.from(PERIODO.fimExclusivo().plusSeconds(1800)));
        f.consultas.add(fora);
        f.fila.add(atendida(fora.getObjectId("_id")));
        var r = new IndicadoresMongoRepository(f).ler(PERIODO, Optional.empty());
        assertEquals(1, r.consultas().agendadas());
        assertEquals(2, r.fila().preenchidas());
        assertEquals(0, r.fila().inscricoesComVinculoInvalido());
        assertTrue(f.chamadas.contains("vinculadas"));
    }

    @Test void buscaSobreposicoesAteOFimDoUltimoSlotAlemDoPeriodoDaClinica() {
        var f = new FontesIndicadoresEmMemoria();
        f.medicos.add(MEDICO);
        f.agendas.add(new Document("medicoId", MEDICO).append("fusoHorario", "UTC").append("duracaoMinutos", 60)
                .append("jornadas", List.of(new Document("diaSemana", 2).append("faixas", List.of(new Document("inicioMinuto", 150).append("fimMinuto", 210))))));
        f.consultas.add(new Document(intervalo("2026-11-24T03:00:00Z", "2026-11-24T03:30:00Z"))
                .append("_id", new ObjectId()).append("medicoId", MEDICO).append("status", "CONCLUIDA"));
        var r = new IndicadoresMongoRepository(f).ler(PERIODO, Optional.of(MEDICO));
        assertEquals(0, r.consultas().concluidas());
        assertEquals(1, r.capacidade().total());
        assertEquals(1, r.capacidade().ocupadas());
        assertTrue(f.janelas.stream().allMatch(j -> j.inicio().equals(Instant.parse("2026-11-24T02:30:00Z"))
                && j.fim().equals(Instant.parse("2026-11-24T03:30:00Z"))));
    }

    @Test void medicoSemAgendaESemConsultaNoPeriodoNaoDeixaAOcupacaoDaClinicaIncompleta() {
        var f = fixture();
        f.medicos.add(new ObjectId()); // ex.: médico inativo da carga de demonstração (#48)
        var r = new IndicadoresMongoRepository(f).ler(PERIODO, Optional.empty());
        assertEquals(0, r.capacidade().medicosSemAgenda());
        var comConsulta = fixture();
        ObjectId outro = new ObjectId();
        comConsulta.medicos.add(outro);
        comConsulta.consultas.add(new Document(comConsulta.consultas.getFirst()).append("_id", new ObjectId()).append("medicoId", outro));
        assertEquals(1, new IndicadoresMongoRepository(comConsulta).ler(PERIODO, Optional.empty()).capacidade().medicosSemAgenda(),
                "com consulta e sem agenda a ocupação continua incompleta");
    }

    @Test void agendaAusenteNaoViraAgendaConfiguradaVazia() {
        var f = fixture();
        f.agendas.clear();
        var r = new IndicadoresMongoRepository(f).ler(PERIODO, Optional.of(MEDICO));
        assertEquals(1, r.capacidade().medicosSemAgenda());
        assertEquals(0, r.capacidade().total());
        assertEquals(4, r.confirmacao().registradas());
        assertTrue(f.janelas.isEmpty());
    }

    @Test void cadastroInexistenteRetorna404AntesDasDemaisFontes() {
        var f = fixture();
        var erro = assertThrows(ApiException.class, () -> new IndicadoresMongoRepository(f).ler(PERIODO, Optional.of(new ObjectId())));
        assertEquals(404, erro.status());
        assertEquals(List.of("medicos"), f.chamadas);
    }

    @Test void falhaMongoRetorna503SemFabricarContagens() {
        var f = new FontesIndicadoresEmMemoria() {
            @Override public List<ObjectId> medicos(Optional<ObjectId> medico) { throw new MongoException("indisponível"); }
        };
        assertEquals(503, assertThrows(ApiException.class, () -> new IndicadoresMongoRepository(f).ler(PERIODO, Optional.empty())).status());
    }

    @Test void projecaoPendenteELegadoContinuamSinalizadosPelaFonteReal() {
        var f = fixture();
        f.consultas.getFirst().put("eventosPendentes", List.of(new Document("_id", new ObjectId())));
        ObjectId legado = f.consultas.getLast().getObjectId("_id");
        f.eventos.removeIf(e -> legado.equals(e.getObjectId("consultaId")));
        var r = new IndicadoresMongoRepository(f).ler(PERIODO, Optional.empty());
        assertEquals(1, r.confirmacao().comProjecaoPendente());
        assertEquals(1, r.confirmacao().semBaseHistorica());
        assertEquals(3, r.confirmacao().registradas());
        assertEquals(1, f.consultas.getFirst().getList("eventosPendentes", Document.class).size());
    }

    @Test void semJornadaNoDiaEvitaLeiturasInuteisSemRemoverConsultas() {
        var f = fixture();
        f.agendas.getFirst().put("jornadas", List.of());
        var r = new IndicadoresMongoRepository(f).ler(PERIODO, Optional.of(MEDICO));
        assertEquals(0, r.capacidade().total());
        assertEquals(0, r.capacidade().medicosSemAgenda());
        assertEquals(4, r.confirmacao().registradas());
        assertTrue(f.janelas.isEmpty());
    }
}
