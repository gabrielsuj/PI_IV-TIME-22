package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.Rotas;
import br.edu.puccampinas.consultafacil.api.handler.CadastroHandler;
import br.edu.puccampinas.consultafacil.api.middleware.Cors;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.security.*;
import br.edu.puccampinas.consultafacil.service.*;
import br.edu.puccampinas.consultafacil.support.CadastrosEmMemoria;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** HTTP e autorização reais; persistência em memória, sem acessar bancos do usuário. */
class CadastroHandlerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Sessoes sessoes = new Sessoes(Duration.ofMinutes(60));
    private HttpServer server;
    private HttpClient client;
    private String base;
    private String admin;

    @BeforeEach void iniciar() throws Exception {
        var dados = new CadastrosEmMemoria();
        var cors = new Cors(List.of("http://localhost:5173"));
        var autz = new Autorizacao(new Autenticador(sessoes));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/medicos", Rotas.catalogo(cors, autz,
                CadastroHandler.medicos(new MedicoService(dados.medicoRepo, dados.especialidadeRepo))));
        server.createContext("/especialidades", Rotas.catalogo(cors, autz,
                CadastroHandler.especialidades(new EspecialidadeService(dados.especialidadeRepo, dados.medicoRepo))));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
        admin = token(Perfil.SECRETARIA_ADMIN);
    }

    @AfterEach void encerrar() {
        if (client != null) client.close();
        if (server != null) server.stop(0);
    }

    @Test void fluxoCompletoComIdsExplicitosDesativacaoEConflitos() throws Exception {
        var criada = enviar("POST", "/especialidades", especialidade(true), admin);
        assertEquals(201, criada.statusCode());
        String eid = json.readTree(criada.body()).get("id").asText();
        assertEquals("/especialidades/" + eid, criada.headers().firstValue("Location").orElseThrow());
        assertEquals(409, enviar("POST", "/especialidades", especialidade(true), admin).statusCode());
        var mr = enviar("POST", "/medicos", medico(eid, true), admin);
        assertEquals(201, mr.statusCode());
        JsonNode m = json.readTree(mr.body());
        String mid = m.get("id").asText();
        assertEquals("000123", m.get("crmNumero").asText());
        assertTrue(m.get("especialidadeIds").get(0).isTextual());
        assertEquals(eid, m.get("especialidadeIds").get(0).asText());
        assertFalse(m.has("_id"));
        assertEquals(409, enviar("POST", "/medicos", medico(eid, true), admin).statusCode());
        assertEquals(1, json.readTree(enviar("GET", "/medicos", null, admin).body()).size());
        assertEquals(200, enviar("GET", "/medicos/" + mid, null, admin).statusCode());
        assertEquals(409, enviar("PUT", "/especialidades/" + eid, especialidade(false), admin).statusCode());
        assertEquals(200, enviar("PUT", "/medicos/" + mid, medico(eid, false), admin).statusCode());
        assertEquals(200, enviar("PUT", "/especialidades/" + eid, especialidade(false), admin).statusCode());
        assertFalse(json.readTree(enviar("GET", "/especialidades/" + eid, null, admin).body()).get("ativa").asBoolean());
        assertFalse(json.readTree(enviar("GET", "/medicos/" + mid, null, admin).body()).get("ativo").asBoolean());
        assertEquals(1, json.readTree(enviar("GET", "/especialidades", null, admin).body()).size());
        assertEquals(400, enviar("POST", "/medicos", medico(eid, true), admin).statusCode());
    }

    @ParameterizedTest @ValueSource(strings = {"/medicos", "/especialidades"})
    void matrizDeAutorizacaoECors(String rota) throws Exception {
        for (String caminho : List.of(rota, rota + "/100000000000000000000001")) {
            for (String metodo : List.of("GET", "POST", "PUT")) {
                assertEquals(401, enviar(metodo, caminho, "{}", null).statusCode());
                assertEquals(401, enviar(metodo, caminho, "{}", "inventado").statusCode());
                for (Perfil perfil : List.of(Perfil.PACIENTE, Perfil.MEDICO)) {
                    int status = enviar(metodo, caminho, "{}", token(perfil)).statusCode();
                    assertEquals(metodo.equals("GET") ? caminho.equals(rota) ? 200 : 404 : 403, status);
                }
            }
        }
        var preflight = client.send(HttpRequest.newBuilder(URI.create(base + rota))
                .header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "POST")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, preflight.statusCode());
        assertEquals("http://localhost:5173", preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
    }

    @ParameterizedTest @ValueSource(strings = {
        "null", "[]", "{", "{} {}",
        "{\"codigo\":\"teste\",\"nome\":\"Teste\",\"ativa\":\"true\"}",
        "{\"codigo\":\"teste\",\"nome\":12,\"ativa\":true}",
        "{\"codigo\":\"teste\",\"nome\":\"Teste\",\"ativa\":true,\"descricao\":\"Não descartar\"}",
        "{\"codigo\":\"teste\",\"nome\":\"Teste\"}"
    }) void recusaCorposInvalidosECamposForaDoSchema(String corpo) throws Exception {
        assertEquals(400, enviar("POST", "/especialidades", corpo, admin).statusCode());
    }

    @Test void recusaTiposCoerciveisDeCrmListasEFlags() throws Exception {
        String eid = json.readTree(enviar("POST", "/especialidades", especialidade(true), admin).body()).get("id").asText();
        String correto = medico(eid, true);
        for (String incorreto : List.of(correto.replace("\"000123\"", "123"),
                correto.replace("true", "\"true\""), correto.replace("[\"" + eid + "\"]", "[123]"),
                correto.replace("[\"" + eid + "\"]", "[]"))) {
            assertEquals(400, enviar("POST", "/medicos", incorreto, admin).statusCode());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"/medicos", "/especialidades"})
    void distingueRotasIdsRegistrosEMetodos(String rota) throws Exception {
        assertEquals(200, enviar("GET", rota + "/", null, admin).statusCode());
        assertEquals(400, enviar("GET", rota + "/abc", null, admin).statusCode());
        assertEquals(404, enviar("GET", rota + "/100000000000000000000099", null, admin).statusCode());
        assertEquals(404, enviar("GET", rota + "errado", null, admin).statusCode());
        assertEquals(404, enviar("GET", rota + "/id/outro", null, admin).statusCode());
        assertEquals(405, enviar("DELETE", rota + "/100000000000000000000099", null, admin).statusCode());
        assertEquals("GET, POST", enviar("PUT", rota, "{}", admin).headers().firstValue("Allow").orElseThrow());
        assertEquals(400, enviar("POST", rota, " ".repeat(16385), admin).statusCode());
    }

    private static String especialidade(boolean ativa) {
        return "{\"codigo\":\"cardiologia\",\"nome\":\"Cardiologia\",\"ativa\":" + ativa + "}";
    }

    private static String medico(String eid, boolean ativo) {
        return "{\"nome\":\"Ana Teste\",\"crmNumero\":\"000123\",\"crmUf\":\"SP\",\"especialidadeIds\":[\"" + eid + "\"],\"ativo\":" + ativo + "}";
    }

    private String token(Perfil perfil) {
        return sessoes.criar(new UsuarioLogado("u-" + perfil, "Teste", perfil)).token();
    }

    private HttpResponse<String> enviar(String metodo, String rota, String corpo, String token) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(base + rota))
                .method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(corpo));
        if (token != null) req.header("Authorization", "Bearer " + token);
        return client.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }
}
