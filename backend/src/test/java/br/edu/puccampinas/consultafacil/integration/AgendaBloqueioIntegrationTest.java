package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.AgendaRequest;
import br.edu.puccampinas.consultafacil.api.dto.AgendaResponse;
import br.edu.puccampinas.consultafacil.api.dto.BloqueioRequest;
import br.edu.puccampinas.consultafacil.api.dto.BloqueioResponse;
import br.edu.puccampinas.consultafacil.api.dto.FaixaDto;
import br.edu.puccampinas.consultafacil.api.dto.JornadaDto;
import br.edu.puccampinas.consultafacil.config.MongoConnection;
import br.edu.puccampinas.consultafacil.repository.AgendaRepository;
import br.edu.puccampinas.consultafacil.repository.BloqueioRepository;
import br.edu.puccampinas.consultafacil.repository.ConsultaRepository;
import br.edu.puccampinas.consultafacil.repository.MedicoRepository;
import br.edu.puccampinas.consultafacil.repository.NomesRepository;
import br.edu.puccampinas.consultafacil.service.AgendaService;
import br.edu.puccampinas.consultafacil.service.BloqueioService;
import br.edu.puccampinas.consultafacil.service.ConsultasAfetadas;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Requer o MongoDB local (docker compose up -d mongo). Usa e limpa o banco consultafacil_test. */
class AgendaBloqueioIntegrationTest {
    private static final ObjectId ANA = new ObjectId("100000000000000000000001");
    private static final ObjectId INEXISTENTE = new ObjectId("100000000000000000000099");

    private static MongoConnection mongo;
    private AgendaService agendas;
    private BloqueioService bloqueios;

    @BeforeAll
    static void conectar() {
        mongo = new MongoConnection("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000", "consultafacil_test");
    }

    @AfterAll
    static void fechar() { mongo.close(); }

    @BeforeEach
    void preparar() {
        var db = mongo.database();
        for (String c : List.of("bloqueios", "agendas", "medicos", "consultas")) db.getCollection(c).deleteMany(new Document());
        db.getCollection("medicos").insertOne(new Document("_id", ANA)
                .append("nome", "Ana Martins — Fictícia")
                .append("crmNumero", "000123")
                .append("crmUf", "SP")
                .append("especialidadeIds", List.of(new ObjectId("000000000000000000000001")))
                .append("ativo", true));

        var medicos = new MedicoRepository(db);
        var agendaRepo = new AgendaRepository(db);
        var afetadas = new ConsultasAfetadas(new ConsultaRepository(db), new NomesRepository.Mongo(db));
        agendas = new AgendaService(agendaRepo, medicos, afetadas, Clock.systemUTC());
        bloqueios = new BloqueioService(new BloqueioRepository(db), agendaRepo, medicos, afetadas);
    }

    private static AgendaRequest agendaDaAna() {
        return new AgendaRequest(30, "America/Sao_Paulo", List.of(
                new JornadaDto(1, List.of(new FaixaDto(480, 720), new FaixaDto(780, 1020))),
                new JornadaDto(3, List.of(new FaixaDto(480, 720)))));
    }

    private static BloqueioRequest bloqueio(String inicio, String fim) {
        return new BloqueioRequest(ANA.toHexString(), inicio, fim, "Teste");
    }

    private static void assertStatus(int esperado, Runnable acao) {
        ApiException e = assertThrows(ApiException.class, acao::run);
        assertEquals(esperado, e.status());
    }

    // ---------- Agenda (#18) ----------

    @Test
    void deveSalvarEBuscarAgenda() {
        agendas.salvar(ANA, agendaDaAna());
        AgendaResponse a = agendas.buscar(ANA);
        assertEquals(30, a.duracaoMinutos());
        assertEquals(2, a.jornadas().size());
        assertEquals(2, a.jornadas().get(0).faixas().size());
    }

    @Test
    void deveSubstituirAgendaAoSalvarNovamente() {
        agendas.salvar(ANA, agendaDaAna());
        agendas.salvar(ANA, new AgendaRequest(20, null, List.of(new JornadaDto(5, List.of(new FaixaDto(480, 600))))));
        AgendaResponse a = agendas.buscar(ANA);
        assertEquals(20, a.duracaoMinutos());
        assertEquals(1, a.jornadas().size());
        assertEquals(1, mongo.database().getCollection("agendas").countDocuments());
    }

    @Test
    void deveRetornar404ParaAgendaNaoConfigurada() {
        assertStatus(404, () -> agendas.buscar(ANA));
    }

    @Test
    void deveRetornar404ParaMedicoInexistente() {
        assertStatus(404, () -> agendas.salvar(INEXISTENTE, agendaDaAna()));
    }

    // ---------- Bloqueios (#19) ----------

    @Test
    void deveCriarEListarBloqueio() {
        BloqueioResponse b = bloqueios.criar(bloqueio("2026-11-09T14:00", "2026-11-09T15:00"));
        assertEquals("2026-11-09T14:00", b.inicio());
        assertEquals("Teste", b.motivo());
        assertEquals(1, bloqueios.listar(ANA, "2026-11-09", "2026-11-09").size());
        assertEquals(0, bloqueios.listar(ANA, "2026-11-10", "2026-11-10").size());
    }

    @Test
    void deveGravarBloqueioEmUtc() {
        bloqueios.criar(bloqueio("2026-11-09T09:00", "2026-11-09T10:00"));
        Document d = mongo.database().getCollection("bloqueios").find().first();
        assertEquals(Instant.parse("2026-11-09T12:00:00Z"), d.getDate("inicio").toInstant());
    }

    @Test
    void deveRecusarBloqueioComFimAntesDoInicio() {
        assertStatus(400, () -> bloqueios.criar(bloqueio("2026-11-09T15:00", "2026-11-09T14:00")));
    }

    @Test
    void deveRecusarBloqueioSobreposto() {
        bloqueios.criar(bloqueio("2026-11-09T14:00", "2026-11-09T15:00"));
        assertStatus(409, () -> bloqueios.criar(bloqueio("2026-11-09T14:30", "2026-11-09T16:00")));
    }

    @Test
    void deveAceitarBloqueioEncostado() {
        bloqueios.criar(bloqueio("2026-11-09T14:00", "2026-11-09T15:00"));
        assertDoesNotThrow(() -> bloqueios.criar(bloqueio("2026-11-09T15:00", "2026-11-09T16:00")));
    }

    @Test
    void deveRemoverBloqueio() {
        ObjectId id = new ObjectId(bloqueios.criar(bloqueio("2026-11-09T14:00", "2026-11-09T15:00")).id());
        bloqueios.remover(id);
        assertStatus(404, () -> bloqueios.remover(id));
    }
}
