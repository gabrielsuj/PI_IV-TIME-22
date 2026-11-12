package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.ConflitoComConsultasException;
import br.edu.puccampinas.consultafacil.api.dto.AgendaRequest;
import br.edu.puccampinas.consultafacil.api.dto.BloqueioRequest;
import br.edu.puccampinas.consultafacil.api.dto.ConsultaCalendarioResponse;
import br.edu.puccampinas.consultafacil.api.dto.FaixaDto;
import br.edu.puccampinas.consultafacil.api.dto.JornadaDto;
import br.edu.puccampinas.consultafacil.api.handler.BloqueioHandler;
import br.edu.puccampinas.consultafacil.config.MongoConnection;
import br.edu.puccampinas.consultafacil.repository.AgendaRepository;
import br.edu.puccampinas.consultafacil.repository.BloqueioRepository;
import br.edu.puccampinas.consultafacil.repository.ConsultaRepository;
import br.edu.puccampinas.consultafacil.repository.MedicoRepository;
import br.edu.puccampinas.consultafacil.repository.NomesRepository;
import br.edu.puccampinas.consultafacil.service.AgendaService;
import br.edu.puccampinas.consultafacil.service.BloqueioService;
import br.edu.puccampinas.consultafacil.service.ConsultasAfetadas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Alterações de agenda e bloqueios com consultas marcadas (#44).
 * Requer o MongoDB local; usa e limpa o banco consultafacil_test.
 * Relógio fixo em 01/11/2026: consultas de 09/11 e 11/11 são futuras; a de 26/10 é passada.
 */
class ConflitosAgendaIntegrationTest {
    private static final ObjectId ANA = new ObjectId("100000000000000000000001");
    private static final ObjectId CLINICA = new ObjectId("000000000000000000000001");
    private static final ObjectId PAULO = new ObjectId("400000000000000000000001");
    private static final Clock AGORA = Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC);

    private static MongoConnection mongo;
    private AgendaService agendas;
    private BloqueioService bloqueios;
    private ObjectId segunda9h, quarta8h;

    @BeforeAll static void conectar() {
        mongo = new MongoConnection("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000", "consultafacil_test");
    }

    @AfterAll static void fechar() { mongo.close(); }

    @BeforeEach void preparar() {
        var db = mongo.database();
        for (String c : List.of("consultas", "agendas", "bloqueios", "medicos", "pacientes", "especialidades"))
            db.getCollection(c).deleteMany(new Document());
        db.getCollection("especialidades").insertOne(new Document("_id", CLINICA)
                .append("codigo", "clinica-geral").append("nome", "Clínica Geral").append("ativa", true));
        db.getCollection("medicos").insertOne(new Document("_id", ANA).append("nome", "Ana Martins")
                .append("crmNumero", "000123").append("crmUf", "SP").append("especialidadeIds", List.of(CLINICA)).append("ativo", true));
        db.getCollection("pacientes").insertOne(new Document("_id", PAULO).append("nome", "Paulo Paciente")
                .append("cpf", "39053344705").append("email", "paulo@example.com"));

        var agendaRepo = new AgendaRepository(db);
        var medicos = new MedicoRepository(db);
        var afetadas = new ConsultasAfetadas(new ConsultaRepository(db), new NomesRepository.Mongo(db));
        agendas = new AgendaService(agendaRepo, medicos, afetadas, AGORA);
        bloqueios = new BloqueioService(new BloqueioRepository(db), agendaRepo, medicos, afetadas);
        agendas.salvar(ANA, jornadaAtual(), true);

        segunda9h = consulta("2026-11-09T12:00:00Z", "AGENDADA");   // seg 09:00 SP
        quarta8h = consulta("2026-11-11T11:00:00Z", "CONFIRMADA");  // qua 08:00 SP
        consulta("2026-11-09T13:00:00Z", "CANCELADA");              // seg 10:00 SP: não conta
        consulta("2026-10-26T12:00:00Z", "AGENDADA");               // seg passada: não conta
    }

    private static ObjectId consulta(String inicioUtc, String status) {
        Instant inicio = Instant.parse(inicioUtc);
        var d = new Document("medicoId", ANA).append("pacienteId", PAULO).append("especialidadeId", CLINICA)
                .append("inicio", Date.from(inicio)).append("fim", Date.from(inicio.plusSeconds(1800)))
                .append("status", status).append("criadaEm", new Date());
        mongo.database().getCollection("consultas").insertOne(d);
        return d.getObjectId("_id");
    }

    /** Seg 08–12 e 13–17, qua 08–12, consultas de 30 min. */
    private static AgendaRequest jornadaAtual() {
        return jornada(new JornadaDto(1, List.of(new FaixaDto(480, 720), new FaixaDto(780, 1020))),
                new JornadaDto(3, List.of(new FaixaDto(480, 720))));
    }

    private static AgendaRequest jornada(JornadaDto... dias) {
        return new AgendaRequest(30, "America/Sao_Paulo", List.of(dias));
    }

    private static BloqueioRequest bloqueio(String inicio, String fim) {
        return new BloqueioRequest(ANA.toHexString(), inicio, fim, "Congresso");
    }

    private static List<String> ids(ConflitoComConsultasException e) {
        return e.consultas().stream().map(ConsultaCalendarioResponse::id).toList();
    }

    // ---------- Bloqueios ----------

    @Test void bloqueioSobreConsultaAtivaRecusaListandoAsConsultas() {
        var e = assertThrows(ConflitoComConsultasException.class,
                () -> bloqueios.criar(bloqueio("2026-11-09T08:00", "2026-11-09T12:00"), false));
        assertEquals(409, e.status());
        assertEquals(List.of(segunda9h.toHexString()), ids(e), "só a ativa; a cancelada das 10h não conta");
        ConsultaCalendarioResponse c = e.consultas().get(0);
        assertEquals("Paulo Paciente", c.pacienteNome());
        assertEquals("2026-11-09T09:00", c.inicio());
        assertTrue(bloqueios.listar(ANA, "2026-11-09", "2026-11-09").isEmpty(), "nada foi gravado");
    }

    @Test void bloqueioConfirmadoGravaEMantemAConsultaParaRemarcar() {
        assertNotNull(bloqueios.criar(bloqueio("2026-11-09T08:00", "2026-11-09T12:00"), true).id());
        var consulta = mongo.database().getCollection("consultas").find(new Document("_id", segunda9h)).first();
        assertEquals("AGENDADA", consulta.getString("status"));
    }

    @Test void bloqueioSemConsultasNaoPedeConfirmacao() {
        assertNotNull(bloqueios.criar(bloqueio("2026-11-09T13:00", "2026-11-09T17:00"), false).id());
    }

    // ---------- Jornada ----------

    @Test void tirarODiaDeUmaConsultaRecusaListandoAConsulta() {
        var semQuarta = jornada(new JornadaDto(1, List.of(new FaixaDto(480, 720), new FaixaDto(780, 1020))));
        var e = assertThrows(ConflitoComConsultasException.class, () -> agendas.salvar(ANA, semQuarta, false));
        assertEquals(List.of(quarta8h.toHexString()), ids(e));
    }

    @Test void encurtarAFaixaRecusaAsConsultasQueFicamDeFora() {
        var segundaDasDez = jornada(new JornadaDto(1, List.of(new FaixaDto(600, 720))), new JornadaDto(3, List.of(new FaixaDto(480, 720))));
        var e = assertThrows(ConflitoComConsultasException.class, () -> agendas.salvar(ANA, segundaDasDez, false));
        assertEquals(List.of(segunda9h.toHexString()), ids(e), "a de 26/10 já passou e não conta");
    }

    @Test void jornadaQueContinuaCobrindoAsConsultasSalvaDireto() {
        var comSexta = jornada(new JornadaDto(1, List.of(new FaixaDto(480, 720))), new JornadaDto(3, List.of(new FaixaDto(480, 720))),
                new JornadaDto(5, List.of(new FaixaDto(480, 720))));
        assertEquals(3, agendas.salvar(ANA, comSexta, false).jornadas().size());
    }

    @Test void jornadaConfirmadaSalvaMesmoDeixandoConsultasDeFora() {
        var soSegunda = jornada(new JornadaDto(1, List.of(new FaixaDto(480, 720))));
        assertEquals(1, agendas.salvar(ANA, soSegunda, true).jornadas().size());
    }

    // ---------- HTTP: o 409 leva a lista; ?confirmar=true grava ----------

    @Test void respostaHttpDoConflitoTrazAsConsultasEConfirmarGrava() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bloqueios", ex -> { try { new BloqueioHandler(bloqueios).handle(ex); } finally { ex.close(); } });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/bloqueios";
            String corpo = "{\"medicoId\":\"" + ANA.toHexString() + "\",\"inicio\":\"2026-11-09T08:00\",\"fim\":\"2026-11-09T12:00\"}";
            var post = (java.util.function.Function<String, HttpRequest>) url -> HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(corpo)).build();

            HttpResponse<String> conflito = client.send(post.apply(base), HttpResponse.BodyHandlers.ofString());
            assertEquals(409, conflito.statusCode());
            JsonNode json = new ObjectMapper().readTree(conflito.body());
            assertEquals(409, json.get("status").asInt());
            assertFalse(json.get("erro").asText().isBlank());
            assertEquals("Paulo Paciente", json.get("consultas").get(0).get("pacienteNome").asText());

            assertEquals(201, client.send(post.apply(base + "?confirmar=true"), HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            server.stop(0);
        }
    }
}
