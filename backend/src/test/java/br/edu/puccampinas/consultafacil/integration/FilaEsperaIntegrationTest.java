package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.Rotas;
import br.edu.puccampinas.consultafacil.api.handler.FilaEsperaHandler;
import br.edu.puccampinas.consultafacil.api.middleware.Cors;
import br.edu.puccampinas.consultafacil.config.MongoConnection;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.repository.ConsultaRepository;
import br.edu.puccampinas.consultafacil.repository.EspecialidadeRepository;
import br.edu.puccampinas.consultafacil.repository.FilaEsperaRepository;
import br.edu.puccampinas.consultafacil.repository.HistoricoRepository;
import br.edu.puccampinas.consultafacil.repository.MedicoRepository;
import br.edu.puccampinas.consultafacil.repository.NomesRepository;
import br.edu.puccampinas.consultafacil.repository.NotificacaoRepository;
import br.edu.puccampinas.consultafacil.repository.PacienteDoUsuarioRepository;
import br.edu.puccampinas.consultafacil.repository.PacienteRepository;
import br.edu.puccampinas.consultafacil.security.Autenticador;
import br.edu.puccampinas.consultafacil.security.Autorizacao;
import br.edu.puccampinas.consultafacil.security.Sessoes;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import br.edu.puccampinas.consultafacil.service.FilaEsperaService;
import br.edu.puccampinas.consultafacil.service.HistoricoProcessor;
import br.edu.puccampinas.consultafacil.service.RegistroConsultaService;
import br.edu.puccampinas.consultafacil.service.SinalizacaoFilaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.sun.net.httpserver.HttpServer;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fila de espera pela API (#31): POST/GET /fila-espera e DELETE /fila-espera/{id}, com o MongoDB real
 * e os validadores do banco. O último teste liga a inscrição à sinalização da #32.
 *
 * Usa o banco consultafacil_test e só apaga os próprios dados (IDs com prefixo d…).
 * Relógio parado em 20/10/2026 12:00Z (09:00 em São Paulo).
 */
class FilaEsperaIntegrationTest {
    private static final ObjectId ESPECIALIDADE = new ObjectId("d00000000000000000000001");
    private static final ObjectId OUTRA_ESPECIALIDADE = new ObjectId("d00000000000000000000002");
    private static final ObjectId ESPECIALIDADE_INATIVA = new ObjectId("d00000000000000000000003");
    private static final ObjectId MEDICO = new ObjectId("d10000000000000000000001");
    private static final ObjectId MEDICO_OUTRA = new ObjectId("d10000000000000000000002");
    private static final ObjectId P1 = new ObjectId("d40000000000000000000001"); // login U1
    private static final ObjectId P2 = new ObjectId("d40000000000000000000002"); // login U2
    private static final ObjectId P3 = new ObjectId("d40000000000000000000003"); // sem login
    private static final String U1 = "d50000000000000000000001";
    private static final String U2 = "d50000000000000000000002";
    private static final Instant AGORA = Instant.parse("2026-10-20T12:00:00Z");

    private static MongoConnection mongo;
    private final ObjectMapper json = new ObjectMapper();
    private MongoDatabase db;
    private HttpServer server;
    private HttpClient client;
    private String base;
    private Sessoes sessoes;
    private RegistroConsultaService registro;

    @BeforeAll static void conectar() {
        mongo = new MongoConnection("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000", "consultafacil_test");
    }

    @AfterAll static void fechar() { mongo.close(); }

    @BeforeEach void preparar() throws Exception {
        db = mongo.database();
        List<ObjectId> pacientes = List.of(P1, P2, P3);
        db.getCollection("filaEspera").deleteMany(Filters.in("pacienteId", pacientes));
        db.getCollection("notificacoes").deleteMany(Filters.in("destinatarioId", pacientes));
        db.getCollection("consultas").deleteMany(Filters.eq("medicoId", MEDICO));
        db.getCollection("historico").deleteMany(Filters.eq("medicoId", MEDICO));
        salvar("especialidades", new Document("_id", ESPECIALIDADE).append("codigo", "teste-fila-api-1")
                .append("nome", "Cardiologia Fila").append("ativa", true));
        salvar("especialidades", new Document("_id", OUTRA_ESPECIALIDADE).append("codigo", "teste-fila-api-2")
                .append("nome", "Dermatologia Fila").append("ativa", true));
        salvar("especialidades", new Document("_id", ESPECIALIDADE_INATIVA).append("codigo", "teste-fila-api-3")
                .append("nome", "Inativa Fila").append("ativa", false));
        salvar("medicos", new Document("_id", MEDICO).append("nome", "Dra. Paula Fila")
                .append("crmNumero", "9600001").append("crmUf", "RR")
                .append("especialidadeIds", List.of(ESPECIALIDADE)).append("ativo", true));
        salvar("medicos", new Document("_id", MEDICO_OUTRA).append("nome", "Dr. Outra Fila")
                .append("crmNumero", "9600002").append("crmUf", "RR")
                .append("especialidadeIds", List.of(OUTRA_ESPECIALIDADE)).append("ativo", true));
        salvar("pacientes", new Document("_id", P1).append("nome", "Ana Fila").append("cpf", "96000000001")
                .append("email", "fila.api1@consultafacil.test").append("usuarioId", new ObjectId(U1)));
        salvar("pacientes", new Document("_id", P2).append("nome", "Bruno Fila").append("cpf", "96000000002")
                .append("email", "fila.api2@consultafacil.test").append("usuarioId", new ObjectId(U2)));
        salvar("pacientes", new Document("_id", P3).append("nome", "Carla Fila").append("cpf", "96000000003")
                .append("email", "fila.api3@consultafacil.test"));

        Clock relogio = Clock.fixed(AGORA, ZoneOffset.UTC);
        var nomes = new NomesRepository.Mongo(db);
        var service = new FilaEsperaService(new FilaEsperaRepository(db), new EspecialidadeRepository.Mongo(db),
                new MedicoRepository(db), new PacienteRepository.Mongo(db), new PacienteDoUsuarioRepository(db), nomes, relogio);
        var consultas = new ConsultaRepository(db);
        var historico = new HistoricoProcessor(consultas, new HistoricoRepository.Mongo(db));
        var sinalizacao = new SinalizacaoFilaService(new FilaEsperaRepository(db), new NotificacaoRepository(db),
                consultas, nomes, historico, relogio);
        registro = new RegistroConsultaService(consultas, historico, relogio, sinalizacao::sinalizar);

        sessoes = new Sessoes(Duration.ofMinutes(60));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fila-espera", Rotas.filaEspera(new Cors(List.of()),
                new Autorizacao(new Autenticador(sessoes)), new FilaEsperaHandler(service)));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach void encerrar() {
        if (client != null) client.close();
        if (server != null) server.stop(0);
    }

    @Test void pacienteSeInscreveEConsultaAPropriaFila() throws Exception {
        var r = enviar("POST", "/fila-espera", paciente(U1), Map.of("especialidadeId", ESPECIALIDADE.toHexString(),
                "turnoPreferido", "manha", "dataInicio", "2026-10-25", "dataFim", "2026-11-10"));
        assertEquals(201, r.statusCode(), r.body());
        JsonNode i = json.readTree(r.body());
        assertEquals(P1.toHexString(), i.get("pacienteId").asText());
        assertEquals("Ana Fila", i.get("pacienteNome").asText());
        assertEquals("Cardiologia Fila", i.get("especialidadeNome").asText());
        assertTrue(semValor(i, "medicoId"), "sem médico = qualquer médico");
        assertEquals("MANHA", i.get("turnoPreferido").asText());
        assertEquals("2026-10-25", i.get("dataInicio").asText());
        assertEquals("2026-11-10", i.get("dataFim").asText());
        assertEquals("AGUARDANDO", i.get("status").asText());
        assertEquals("2026-11-11T02:59:59.999Z", i.get("validoAte").asText(), "fim do último dia em São Paulo");
        assertTrue(semValor(i, "ultimaSinalizacao"));

        Document salvo = db.getCollection("filaEspera").find(Filters.eq("_id", new ObjectId(i.get("id").asText()))).first();
        assertEquals(Date.from(Instant.parse("2026-10-25T03:00:00Z")), salvo.getDate("dataInicio"));
        assertEquals("PACIENTE", salvo.get("registradoPor", Document.class).getString("perfil"));
        assertEquals(new ObjectId(U1), salvo.get("registradoPor", Document.class).getObjectId("id"));

        JsonNode lista = json.readTree(enviar("GET", "/fila-espera", paciente(U1), null).body());
        assertEquals(1, lista.size());
        assertEquals(i.get("id"), lista.get(0).get("id"));
        assertEquals(0, json.readTree(enviar("GET", "/fila-espera", paciente(U2), null).body()).size(),
                "outro paciente não vê a inscrição");
    }

    @Test void semPeriodoValePorNoventaDias() throws Exception {
        JsonNode i = json.readTree(inscrever(U1, ESPECIALIDADE, null).body());
        assertEquals("QUALQUER", i.get("turnoPreferido").asText());
        assertTrue(semValor(i, "dataInicio"));
        assertEquals(AGORA.plus(Duration.ofDays(90)).toString(), i.get("validoAte").asText());
    }

    @Test void naoAceitaDuasInscricoesAtivasIguais() throws Exception {
        assertEquals(201, inscrever(U1, ESPECIALIDADE, null).statusCode());
        var repetida = inscrever(U1, ESPECIALIDADE, null);
        assertEquals(409, repetida.statusCode());
        assertFalse(json.readTree(repetida.body()).get("erro").asText().isBlank());
        // Com um médico específico é outra inscrição; outro paciente também pode.
        assertEquals(201, inscrever(U1, ESPECIALIDADE, MEDICO).statusCode());
        assertEquals(201, inscrever(U2, ESPECIALIDADE, null).statusCode());
        assertEquals(409, inscrever(U1, ESPECIALIDADE, MEDICO).statusCode());
    }

    @Test void recusaDadosInvalidos() throws Exception {
        String esp = ESPECIALIDADE.toHexString();
        assertEquals(400, enviar("POST", "/fila-espera", paciente(U1), Map.of("especialidadeId", esp, "turnoPreferido", "NOITE")).statusCode());
        assertEquals(400, enviar("POST", "/fila-espera", paciente(U1), Map.of("especialidadeId", esp, "dataInicio", "2026-10-19")).statusCode());
        assertEquals(400, enviar("POST", "/fila-espera", paciente(U1), Map.of("especialidadeId", esp, "dataInicio", "20/10/2026")).statusCode());
        assertEquals(400, enviar("POST", "/fila-espera", paciente(U1),
                Map.of("especialidadeId", esp, "dataInicio", "2026-11-10", "dataFim", "2026-11-01")).statusCode());
        assertEquals(400, enviar("POST", "/fila-espera", paciente(U1), Map.of("especialidadeId", esp, "dataFim", "2027-01-19")).statusCode(),
                "mais de 90 dias à frente");
        assertEquals(400, inscrever(U1, ESPECIALIDADE, MEDICO_OUTRA).statusCode(), "médico não atende a especialidade");
        assertEquals(400, enviar("POST", "/fila-espera", paciente(U1), Map.of("especialidadeId", "abc")).statusCode());
        assertEquals(404, inscrever(U1, ESPECIALIDADE_INATIVA, null).statusCode());
        assertEquals(404, inscrever(U1, ESPECIALIDADE, new ObjectId()).statusCode());
        assertEquals(403, enviar("POST", "/fila-espera", paciente(U1),
                Map.of("especialidadeId", esp, "pacienteId", P2.toHexString())).statusCode(), "paciente inscrevendo outra pessoa");
        assertEquals(403, inscrever(new ObjectId().toHexString(), ESPECIALIDADE, null).statusCode(), "login sem cadastro de paciente");
        assertEquals(0, db.getCollection("filaEspera").countDocuments(Filters.in("pacienteId", P1, P2)));
    }

    @Test void secretariaInscrevePacienteEFiltraAFila() throws Exception {
        String esp = ESPECIALIDADE.toHexString();
        assertEquals(400, enviar("POST", "/fila-espera", secretaria(), Map.of("especialidadeId", esp)).statusCode(), "sem pacienteId");
        assertEquals(404, enviar("POST", "/fila-espera", secretaria(),
                Map.of("especialidadeId", esp, "pacienteId", new ObjectId().toHexString())).statusCode());
        var r = enviar("POST", "/fila-espera", secretaria(), Map.of("especialidadeId", esp, "pacienteId", P3.toHexString()));
        assertEquals(201, r.statusCode(), r.body());
        Document salvo = db.getCollection("filaEspera").find(Filters.eq("pacienteId", P3)).first();
        assertEquals("SECRETARIA", salvo.get("registradoPor", Document.class).getString("perfil"));
        inscrever(U1, OUTRA_ESPECIALIDADE, null);

        JsonNode doP3 = json.readTree(enviar("GET", "/fila-espera?pacienteId=" + P3.toHexString(), secretaria(), null).body());
        assertEquals(1, doP3.size());
        assertEquals("Carla Fila", doP3.get(0).get("pacienteNome").asText());
        JsonNode daOutra = json.readTree(enviar("GET", "/fila-espera?especialidadeId=" + OUTRA_ESPECIALIDADE.toHexString(),
                secretaria(), null).body());
        assertEquals(List.of(P1.toHexString()), daOutra.findValuesAsText("pacienteId"));
        // O paciente não usa os filtros de outra pessoa: continua vendo só as próprias.
        JsonNode doP1 = json.readTree(enviar("GET", "/fila-espera?pacienteId=" + P3.toHexString(), paciente(U1), null).body());
        assertEquals(List.of(P1.toHexString()), doP1.findValuesAsText("pacienteId"));
    }

    @Test void pacienteSaiDaFilaEPodeEntrarDeNovo() throws Exception {
        String id = json.readTree(inscrever(U1, ESPECIALIDADE, null).body()).get("id").asText();
        assertEquals(403, enviar("DELETE", "/fila-espera/" + id, paciente(U2), null).statusCode(), "inscrição de outra pessoa");

        var r = enviar("DELETE", "/fila-espera/" + id, paciente(U1), null);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("CANCELADA", json.readTree(r.body()).get("status").asText());
        assertEquals(409, enviar("DELETE", "/fila-espera/" + id, paciente(U1), null).statusCode(), "já encerrada");
        assertEquals(404, enviar("DELETE", "/fila-espera/" + new ObjectId().toHexString(), secretaria(), null).statusCode());
        assertEquals(400, enviar("DELETE", "/fila-espera/abc", secretaria(), null).statusCode());

        assertEquals(0, json.readTree(enviar("GET", "/fila-espera", paciente(U1), null).body()).size(), "sem status: só ativas");
        assertEquals(1, json.readTree(enviar("GET", "/fila-espera?status=CANCELADA", paciente(U1), null).body()).size());
        assertEquals(400, enviar("GET", "/fila-espera?status=QUALQUER", paciente(U1), null).statusCode());
        assertEquals(201, inscrever(U1, ESPECIALIDADE, null).statusCode(), "a cancelada não bloqueia uma nova");
    }

    @Test void inscricaoVencidaExpiraENaoBloqueiaUmaNova() throws Exception {
        Date criado = Date.from(AGORA.minus(Duration.ofDays(100)));
        db.getCollection("filaEspera").insertOne(new Document("pacienteId", P1).append("especialidadeId", ESPECIALIDADE)
                .append("medicoId", null).append("turnoPreferido", "QUALQUER").append("status", "AGUARDANDO")
                .append("ultimaSinalizacao", null).append("consultaAgendadaId", null).append("atendidaEm", null)
                .append("validoAte", Date.from(AGORA.minus(Duration.ofDays(10))))
                .append("registradoPor", new Document("perfil", "PACIENTE").append("id", new ObjectId(U1)))
                .append("criadoEm", criado).append("atualizadoEm", criado));

        assertEquals(201, inscrever(U1, ESPECIALIDADE, null).statusCode());
        JsonNode expiradas = json.readTree(enviar("GET", "/fila-espera?status=EXPIRADA", paciente(U1), null).body());
        assertEquals(1, expiradas.size());
        assertEquals(1, json.readTree(enviar("GET", "/fila-espera", paciente(U1), null).body()).size());
    }

    /** Ponta a ponta com a #32: inscrição pela API → consulta compatível cancelada → paciente vê a vaga. */
    @Test void inscricaoSinalizadaMostraAVagaLiberada() throws Exception {
        inscrever(U1, ESPECIALIDADE, null);
        Instant vaga = Instant.parse("2026-10-26T13:00:00Z");
        UsuarioLogado sec = new UsuarioLogado(new ObjectId().toHexString(), "Secretaria", Perfil.SECRETARIA_ADMIN);
        ObjectId consulta = registro.criar(new Document("pacienteId", P2).append("medicoId", MEDICO)
                .append("especialidadeId", ESPECIALIDADE).append("inicio", Date.from(vaga))
                .append("fim", Date.from(vaga.plus(Duration.ofMinutes(30)))), sec).getObjectId("_id");
        registro.alterar(consulta, new Document("status", "CANCELADA"), sec);

        JsonNode i = json.readTree(enviar("GET", "/fila-espera", paciente(U1), null).body()).get(0);
        assertEquals("SINALIZADA", i.get("status").asText());
        JsonNode s = i.get("ultimaSinalizacao");
        assertEquals(consulta.toHexString(), s.get("consultaOrigemId").asText());
        assertEquals(MEDICO.toHexString(), s.get("medicoId").asText());
        assertEquals("Dra. Paula Fila", s.get("medicoNome").asText());
        assertEquals(vaga.toString(), s.get("dataHora").asText());
    }

    // ---- apoio ----

    /** A API (Gson padrão) omite campos null: ausente ou null contam como "sem valor". */
    private static boolean semValor(JsonNode n, String campo) { return n.path(campo).isMissingNode() || n.path(campo).isNull(); }

    private HttpResponse<String> inscrever(String usuario, ObjectId especialidade, ObjectId medico) throws Exception {
        var corpo = new java.util.HashMap<String, Object>(Map.of("especialidadeId", especialidade.toHexString()));
        if (medico != null) corpo.put("medicoId", medico.toHexString());
        return enviar("POST", "/fila-espera", paciente(usuario), corpo);
    }

    private String paciente(String usuarioId) {
        return sessoes.criar(new UsuarioLogado(usuarioId, "Paciente", Perfil.PACIENTE)).token();
    }

    private String secretaria() {
        return sessoes.criar(new UsuarioLogado(new ObjectId().toHexString(), "Secretaria", Perfil.SECRETARIA_ADMIN)).token();
    }

    private HttpResponse<String> enviar(String metodo, String rota, String token, Map<String, ?> corpo) throws Exception {
        var body = corpo == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo));
        var b = HttpRequest.newBuilder(URI.create(base + rota)).method(metodo, body)
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void salvar(String colecao, Document d) {
        db.getCollection(colecao).replaceOne(Filters.eq("_id", d.get("_id")), d, new ReplaceOptions().upsert(true));
    }
}
