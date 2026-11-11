package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.Router;
import br.edu.puccampinas.consultafacil.api.handler.AuthHandler;
import br.edu.puccampinas.consultafacil.api.middleware.Cors;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.domain.model.Usuario;
import br.edu.puccampinas.consultafacil.repository.UsuarioRepository;
import br.edu.puccampinas.consultafacil.security.Autenticador;
import br.edu.puccampinas.consultafacil.security.SenhaHasher;
import br.edu.puccampinas.consultafacil.security.Sessoes;
import br.edu.puccampinas.consultafacil.service.AuthService;
import br.edu.puccampinas.consultafacil.support.RelogioAjustavel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Login, sessão e logout pela API (#9), com usuários em memória. */
class AuthHandlerTest {
    private static final SenhaHasher HASHER = new SenhaHasher(1_000);

    private final RelogioAjustavel relogio = new RelogioAjustavel(Instant.parse("2026-11-12T12:00:00Z"));
    private final EmMemoria usuarios = new EmMemoria();
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private HttpClient client;
    private String base;

    @BeforeEach void iniciar() throws Exception {
        usuarios.add(new Usuario("u1", "Sofia Secretaria", "sofia@clinica.com", Perfil.SECRETARIA_ADMIN, true,
                HASHER.gerar("senha-forte")));
        usuarios.add(new Usuario("u2", "Igor Inativo", "igor@clinica.com", Perfil.MEDICO, false,
                HASHER.gerar("senha-forte")));

        var sessoes = new Sessoes(Duration.ofMinutes(60), relogio);
        var autenticador = new Autenticador(sessoes);
        var handler = new AuthHandler(new AuthService(usuarios, HASHER, sessoes, relogio), autenticador);
        var router = new Router(new Cors(List.of()))
                .add("POST", "/auth/login", handler)
                .add("POST", "/auth/logout", handler)
                .add("GET", "/auth/me", handler);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", router);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach void encerrar() {
        if (client != null) client.close();
        if (server != null) server.stop(0);
    }

    @Test void deveFazerLoginEAcessarRotaProtegida() throws Exception {
        var r = login("  SOFIA@Clinica.com ", "senha-forte");
        assertEquals(200, r.statusCode());
        JsonNode corpo = json.readTree(r.body());
        assertFalse(corpo.get("token").asText().isBlank());
        assertEquals("2026-11-12T13:00:00Z", corpo.get("expiraEm").asText());
        assertEquals("SECRETARIA_ADMIN", corpo.get("usuario").get("perfil").asText());
        assertFalse(r.body().contains("hash"), "a senha não pode sair na resposta");
        assertEquals(Instant.parse("2026-11-12T12:00:00Z"), usuarios.ultimoAcesso.get("u1"));

        var me = enviar("GET", "/auth/me", null, corpo.get("token").asText());
        assertEquals(200, me.statusCode());
        assertEquals("Sofia Secretaria", json.readTree(me.body()).get("nome").asText());
    }

    @Test void credenciaisInvalidasRetornam401() throws Exception {
        assertErro(login("sofia@clinica.com", "senha-errada"), 401);
        assertErro(login("ninguem@clinica.com", "senha-forte"), 401);
    }

    @Test void mensagemIndicaOMotivoDaRecusa() throws Exception {
        assertEquals(AuthService.EMAIL_NAO_CADASTRADO, erro(login("ninguem@clinica.com", "x")));
        assertEquals(AuthService.SENHA_INCORRETA, erro(login("sofia@clinica.com", "x")));
    }

    @Test void usuarioInativoNaoEntra() throws Exception {
        var r = login("igor@clinica.com", "senha-forte");
        assertErro(r, 401);
        assertEquals(AuthService.USUARIO_INATIVO, erro(r));
    }

    @Test void inativoComSenhaErradaNaoDescobreQueEstaDesativado() throws Exception {
        assertEquals(AuthService.SENHA_INCORRETA, erro(login("igor@clinica.com", "x")));
    }

    @Test void camposFaltandoRetornam400() throws Exception {
        assertErro(enviar("POST", "/auth/login", "{\"email\":\"sofia@clinica.com\"}", null), 400);
        assertErro(enviar("POST", "/auth/login", "{}", null), 400);
        assertErro(enviar("POST", "/auth/login", "{", null), 400);
    }

    @Test void semTokenOuTokenInventadoRetorna401() throws Exception {
        assertErro(enviar("GET", "/auth/me", null, null), 401);
        assertErro(enviar("GET", "/auth/me", null, "inventado"), 401);
    }

    @Test void tokenExpiradoRetorna401() throws Exception {
        String token = tokenDaSofia();
        relogio.avancar(Duration.ofMinutes(61));
        assertErro(enviar("GET", "/auth/me", null, token), 401);
    }

    @Test void tokenUsadoDepoisDoLogoutRetorna401() throws Exception {
        String token = tokenDaSofia();
        assertEquals(204, enviar("POST", "/auth/logout", null, token).statusCode());
        assertErro(enviar("GET", "/auth/me", null, token), 401);
    }

    // ---------------------------------------------------------------- apoio

    private String tokenDaSofia() throws Exception {
        return json.readTree(login("sofia@clinica.com", "senha-forte").body()).get("token").asText();
    }

    private HttpResponse<String> login(String email, String senha) throws Exception {
        return enviar("POST", "/auth/login", json.writeValueAsString(Map.of("email", email, "senha", senha)), null);
    }

    private HttpResponse<String> enviar(String metodo, String rota, String body, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + rota))
                .method(metodo, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String erro(HttpResponse<String> r) throws Exception {
        return json.readTree(r.body()).get("erro").asText();
    }

    private void assertErro(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        assertFalse(json.readTree(r.body()).get("erro").asText().isBlank());
    }

    static class EmMemoria implements UsuarioRepository {
        private final Map<String, Usuario> porEmail = new HashMap<>();
        final Map<String, Instant> ultimoAcesso = new HashMap<>();

        void add(Usuario u) { porEmail.put(u.email(), u); }

        @Override public Optional<Usuario> buscarPorEmail(String email) { return Optional.ofNullable(porEmail.get(email)); }
        @Override public void registrarAcesso(String id, Instant quando) { ultimoAcesso.put(id, quando); }
    }
}
