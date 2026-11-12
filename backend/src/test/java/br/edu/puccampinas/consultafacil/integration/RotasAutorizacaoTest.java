package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.Rotas;
import br.edu.puccampinas.consultafacil.api.middleware.Cors;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.security.Autenticador;
import br.edu.puccampinas.consultafacil.security.Autorizacao;
import br.edu.puccampinas.consultafacil.security.Sessoes;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autorização por perfil (#10) usando a tabela real de Rotas.
 * Os handlers de negócio são trocados por um eco que devolve 200 e o perfil de quem chamou,
 * então o teste verifica só a permissão, sem banco.
 */
class RotasAutorizacaoTest {
    private final Sessoes sessoes = new Sessoes(Duration.ofMinutes(60));
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private HttpClient client;
    private String base;

    /** Responde 200 com o perfil que a Autorizacao deixou na requisição. */
    private static final HttpHandler ECO = ex -> {
        UsuarioLogado u = Autorizacao.usuario(ex);
        Http.sendJson(ex, 200, Map.of("perfil", u == null ? "ANONIMO" : u.perfil().name()));
    };

    @BeforeEach void iniciar() throws Exception {
        var cors = new Cors(List.of());
        var autz = new Autorizacao(new Autenticador(sessoes));
        var router = Rotas.router(cors, autz, new Rotas.Handlers(ECO, ECO, ECO, ECO, ECO, ECO, ECO));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", router);
        server.createContext("/pacientes", Rotas.pacientes(cors, autz, ex -> { ECO.handle(ex); }));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach void encerrar() {
        if (client != null) client.close();
        if (server != null) server.stop(0);
    }

    /** Cenário do Quadro 4: acesso a uma função de outro perfil retorna 403. */
    @Test void deveBloquearAcessoDePerfilNaoAutorizado() throws Exception {
        String paciente = token(Perfil.PACIENTE);

        var r = enviar("PUT", "/agendas?medicoId=100000000000000000000001", paciente);
        assertEquals(403, r.statusCode());
        assertEquals(403, json.readTree(r.body()).get("status").asInt());
        assertFalse(json.readTree(r.body()).get("erro").asText().isBlank());
    }

    @ParameterizedTest(name = "{0} {1} como {2} → {3}")
    @CsvSource({
        // Agendas e bloqueios: médico e secretaria
        "GET,    /agendas,        PACIENTE,         403",
        "GET,    /agendas,        MEDICO,           200",
        "PUT,    /agendas,        SECRETARIA_ADMIN, 200",
        "GET,    /bloqueios,      PACIENTE,         403",
        "POST,   /bloqueios,      MEDICO,           200",
        "DELETE, /bloqueios,      SECRETARIA_ADMIN, 200",
        // Pacientes: leitura médico e secretaria; escrita só secretaria
        "GET,    /pacientes,      PACIENTE,         403",
        "GET,    /pacientes,      MEDICO,           200",
        "GET,    /pacientes/abc,  MEDICO,           200",
        "POST,   /pacientes,      MEDICO,           403",
        "PUT,    /pacientes/abc,  MEDICO,           403",
        "POST,   /pacientes,      SECRETARIA_ADMIN, 200",
        "PUT,    /pacientes/abc,  SECRETARIA_ADMIN, 200",
        // Busca de vagas: todos os perfis; agendamento: paciente e secretaria (#21)
        "GET,    /vagas,          PACIENTE,         200",
        "GET,    /vagas,          MEDICO,           200",
        "GET,    /vagas,          SECRETARIA_ADMIN, 200",
        "POST,   /consultas,      PACIENTE,         200",
        "POST,   /consultas,      SECRETARIA_ADMIN, 200",
        "POST,   /consultas,      MEDICO,           403",
    })
    void matrizDePermissoes(String metodo, String rota, Perfil perfil, int esperado) throws Exception {
        var r = enviar(metodo, rota, token(perfil));
        assertEquals(esperado, r.statusCode(), r.body());
        if (esperado == 200) assertEquals(perfil.name(), json.readTree(r.body()).get("perfil").asText());
    }

    @Test void semLoginRetorna401EmRotaProtegida() throws Exception {
        assertEquals(401, enviar("GET", "/agendas", null).statusCode());
        assertEquals(401, enviar("GET", "/pacientes", null).statusCode());
        assertEquals(401, enviar("GET", "/bloqueios", "token-inventado").statusCode());
        assertEquals(401, enviar("GET", "/vagas", null).statusCode());
        assertEquals(401, enviar("POST", "/consultas", null).statusCode());
    }

    @Test void tokenDepoisDoLogoutRetorna401() throws Exception {
        String t = token(Perfil.SECRETARIA_ADMIN);
        sessoes.encerrar(t);
        assertEquals(401, enviar("GET", "/agendas", t).statusCode());
    }

    @Test void rotasPublicasNaoExigemLogin() throws Exception {
        assertEquals(200, enviar("GET", "/health", null).statusCode());
    }

    @Test void preflightCorsNaoExigeLogin() throws Exception {
        assertEquals(204, enviar("OPTIONS", "/agendas", null).statusCode());
        assertEquals(204, enviar("OPTIONS", "/pacientes", null).statusCode());
    }

    // ---------------------------------------------------------------- apoio

    private String token(Perfil perfil) {
        return sessoes.criar(new UsuarioLogado("u-" + perfil, "Teste " + perfil, perfil)).token();
    }

    private HttpResponse<String> enviar(String metodo, String rota, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + rota)).method(metodo, HttpRequest.BodyPublishers.noBody());
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
