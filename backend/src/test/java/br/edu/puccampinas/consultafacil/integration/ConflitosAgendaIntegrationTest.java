package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.ConflitoComConsultasException;
import br.edu.puccampinas.consultafacil.api.Rotas;
import br.edu.puccampinas.consultafacil.api.handler.AgendaHandler;
import br.edu.puccampinas.consultafacil.api.handler.ConsultaCalendarioHandler;
import br.edu.puccampinas.consultafacil.api.middleware.Cors;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.repository.MedicoDoUsuarioRepository;
import br.edu.puccampinas.consultafacil.security.Autenticador;
import br.edu.puccampinas.consultafacil.security.Autorizacao;
import br.edu.puccampinas.consultafacil.security.Sessoes;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import br.edu.puccampinas.consultafacil.service.AcessoAgenda;
import br.edu.puccampinas.consultafacil.service.ConsultaCalendarioService;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
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

    @Nested class Api {
        final ObjectMapper json = new ObjectMapper();
        final ObjectId bruno = new ObjectId();
        final Sessoes sessoes = new Sessoes(Duration.ofMinutes(10));
        HttpServer server;
        HttpClient client;
        String secretaria, tokenAna, tokenBruno;

        String token(ObjectId usuario, Perfil perfil) {
            return sessoes.criar(new UsuarioLogado(usuario.toHexString(), perfil.name(), perfil)).token();
        }

        @BeforeEach void iniciar() throws Exception {
            var db = mongo.database();
            var medicos = db.getCollection("medicos");
            var loginAna = new ObjectId();
            var loginBruno = new ObjectId();
            medicos.updateOne(new Document("_id", ANA), new Document("$set", new Document("usuarioId", loginAna)));
            medicos.insertOne(new Document("_id", bruno).append("usuarioId", loginBruno).append("nome", "Bruno")
                    .append("crmNumero", "000124").append("crmUf", "SP").append("especialidadeIds", List.of(CLINICA)).append("ativo", true));
            agendas.salvar(bruno, jornadaAtual(), true);
            var acesso = new AcessoAgenda(new MedicoDoUsuarioRepository(db));
            var calendario = new ConsultaCalendarioService(new ConsultaRepository(db), new AgendaRepository(db),
                    new MedicoRepository(db), new NomesRepository.Mongo(db));
            var handlers = new Rotas.Handlers(null, null, new AgendaHandler(agendas, acesso),
                    new BloqueioHandler(bloqueios, acesso), null, null, null, new ConsultaCalendarioHandler(calendario, acesso), null, null);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", Rotas.router(new Cors(List.of()), new Autorizacao(new Autenticador(sessoes)), handlers));
            server.start();
            client = HttpClient.newHttpClient();
            tokenAna = token(loginAna, Perfil.MEDICO);
            tokenBruno = token(loginBruno, Perfil.MEDICO);
            secretaria = token(new ObjectId(), Perfil.SECRETARIA_ADMIN);
        }

        @AfterEach void fecharServidor() {
            if (client != null) client.close();
            if (server != null) server.stop(0);
        }

        JsonNode chamar(String token, String metodo, String rota, Object corpo, int status) throws Exception {
            var pedido = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + rota))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo))).build();
            var resposta = client.send(pedido, HttpResponse.BodyHandlers.ofString());
            assertEquals(status, resposta.statusCode(), metodo + " " + rota + " " + resposta.body());
            return json.readTree(resposta.body());
        }

        @Test void respostaHttpDoConflitoTrazAsConsultasEConfirmarGrava() throws Exception {
            var corpo = bloqueio("2026-11-09T08:00", "2026-11-09T12:00");
            var conflito = chamar(secretaria, "POST", "/bloqueios", corpo, 409);
            assertEquals(409, conflito.get("status").asInt());
            assertFalse(conflito.get("erro").asText().isBlank());
            assertEquals("Paulo Paciente", conflito.get("consultas").get(0).get("pacienteNome").asText());
            chamar(secretaria, "POST", "/bloqueios?confirmar=true", corpo, 201);
        }

        @Test void cadaMedicoAcessaSomenteSuaAgendaESecretariaAcessaAmbas() throws Exception {
            verificarEscopo(ANA, tokenAna, tokenBruno);
            verificarEscopo(bruno, tokenBruno, tokenAna);
        }

        private void verificarEscopo(ObjectId medico, String dono, String outro) throws Exception {
            String id = medico.toHexString();
            String agenda = "/agendas?medicoId=" + id;
            String lista = "/bloqueios?medicoId=" + id;
            String calendario = "/consultas?de=2026-11-09&ate=2026-11-09&medicoId=" + id;
            for (String rota : List.of(agenda, lista, calendario))
                chamar(outro, "GET", rota, null, 403);
            var novaJornada = new AgendaRequest(20, "America/Sao_Paulo", jornadaAtual().jornadas());
            chamar(outro, "PUT", agenda + "&confirmar=true", novaJornada, 403);
            assertEquals(30, chamar(dono, "GET", agenda, null, 200).get("duracaoMinutos").asInt());
            assertEquals(id, chamar(dono, "GET", "/agendas?medicoId=meu", null, 200).get("medicoId").asText());
            for (String autorizado : List.of(dono, secretaria)) {
                var jornada = autorizado.equals(dono) ? novaJornada : jornadaAtual();
                chamar(autorizado, "PUT", agenda, jornada, 200);
                var gravada = chamar(autorizado, "GET", agenda, null, 200);
                assertEquals(id, gravada.get("medicoId").asText());
                assertEquals(jornada.duracaoMinutos().intValue(), gravada.get("duracaoMinutos").asInt());
                var registros = chamar(autorizado, "GET", calendario, null, 200);
                assertEquals(medico.equals(ANA) ? 2 : 0, registros.size());
                registros.forEach(c -> assertEquals(id, c.get("medicoId").asText()));
                var corpo = new BloqueioRequest(id, "2026-11-09T15:00", "2026-11-09T16:00", "Reunião");
                chamar(outro, "POST", "/bloqueios?confirmar=true", corpo, 403);
                var salvo = chamar(autorizado, "POST", "/bloqueios", corpo, 201);
                String excluir = "/bloqueios?id=" + salvo.get("id").asText();
                chamar(outro, "DELETE", excluir, null, 404);
                assertEquals(salvo.get("id"), chamar(autorizado, "GET", lista, null, 200).get(0).get("id"));
                chamar(autorizado, "DELETE", excluir, null, 200);
                assertEquals(0, chamar(autorizado, "GET", lista, null, 200).size());
            }
        }

        @Test void medicoSemVinculoNaoRecebeAcessoIrrestrito() throws Exception {
            var semVinculo = token(new ObjectId(), Perfil.MEDICO);
            chamar(semVinculo, "GET", "/agendas?medicoId=meu", null, 403);
            var salvo = bloqueios.criar(bloqueio("2026-11-09T15:00", "2026-11-09T16:00"));
            chamar(semVinculo, "DELETE", "/bloqueios?id=" + salvo.id(), null, 403);
            assertEquals(1, bloqueios.listar(ANA, null, null).size());
        }
    }
}
