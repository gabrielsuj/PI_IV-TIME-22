package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.Rotas;
import br.edu.puccampinas.consultafacil.api.handler.AuthHandler;
import br.edu.puccampinas.consultafacil.api.handler.HealthHandler;
import br.edu.puccampinas.consultafacil.api.handler.PacienteHandler;
import br.edu.puccampinas.consultafacil.api.middleware.Cors;
import br.edu.puccampinas.consultafacil.config.MongoConnection;
import br.edu.puccampinas.consultafacil.domain.exception.ServidorCpfIndisponivelException;
import br.edu.puccampinas.consultafacil.repository.PacienteRepository;
import br.edu.puccampinas.consultafacil.repository.UsuarioRepository;
import br.edu.puccampinas.consultafacil.security.Autenticador;
import br.edu.puccampinas.consultafacil.security.Autorizacao;
import br.edu.puccampinas.consultafacil.security.SenhaHash;
import br.edu.puccampinas.consultafacil.security.SenhaHasher;
import br.edu.puccampinas.consultafacil.security.Sessoes;
import br.edu.puccampinas.consultafacil.service.AuthService;
import br.edu.puccampinas.consultafacil.service.PacienteService;
import br.edu.puccampinas.consultafacil.service.PacienteValidator;
import br.edu.puccampinas.consultafacil.service.ValidadorCpf;
import br.edu.puccampinas.consultafacil.service.ValidadorCpfTcp;
import br.edu.puccampinas.consultafacil.support.RelogioAjustavel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.sun.net.httpserver.HttpServer;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acesso, permissões e pacientes ponta a ponta com o MongoDB real (#50).
 *
 * <p>{@code AuthHandlerTest} e {@code PacienteHandlerTest} usam repositórios em memória. Aqui
 * as mesmas rotas rodam com {@code UsuarioRepository.Mongo}, {@code PacienteRepository.Mongo},
 * os validadores {@code $jsonSchema} e os índices únicos do banco, montadas pelo mesmo
 * {@code Rotas.router} do {@code App}: login → sessão → CRUD de pacientes → logout/expiração.
 *
 * <p>Banco {@code consultafacil_test}. Usuários com IDs de prefixo {@code e5…} e pacientes com
 * e-mail em {@code @acesso.consultafacil.test}; o teste só apaga esses registros.
 *
 * @author Murilo Moraes (criador)
 */
class AcessoPacientesMongoIntegrationTest {
    private static final Instant AGORA = Instant.parse("2026-11-20T12:00:00Z");
    private static final String SENHA = "senha-forte-50";
    private static final String DOMINIO = "@acesso.consultafacil.test";

    private static final ObjectId SECRETARIA = new ObjectId("e50000000000000000000001");
    private static final ObjectId MEDICO = new ObjectId("e50000000000000000000002");
    private static final ObjectId PACIENTE = new ObjectId("e50000000000000000000003");
    private static final ObjectId INATIVO = new ObjectId("e50000000000000000000004");
    private static final List<ObjectId> USUARIOS = List.of(SECRETARIA, MEDICO, PACIENTE, INATIVO);

    // CPFs válidos (dígitos verificadores calculados), reservados para este teste.
    private static final String CPF_ANA = cpfValido("970500001");
    private static final String CPF_BRUNO = cpfValido("970500002");
    private static final String CPF_CARLA = cpfValido("970500003");

    private static MongoConnection mongo;
    private final ObjectMapper json = new ObjectMapper();
    private MongoDatabase db;
    private HttpServer server;
    private HttpClient client;
    private String base;
    private RelogioAjustavel relogio;
    private ExecutorService threadsHttp;
    /** Troca o validador de CPF sem remontar o servidor. Padrão: aceita (o CPF já tem dígitos válidos). */
    private final AtomicReference<ValidadorCpf> validadorCpf = new AtomicReference<>(cpf -> {});

    @BeforeAll static void conectar() {
        mongo = new MongoConnection("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000", "consultafacil_test");
    }

    @AfterAll static void fechar() { mongo.close(); }

    @BeforeEach void preparar() throws Exception {
        db = mongo.database();
        limpar();
        // Poucas iterações: o teste confere o fluxo, não o custo do PBKDF2 (coberto em SenhaHasherTest).
        var hasher = new SenhaHasher(1_000);
        salvarUsuario(SECRETARIA, "Sofia Acesso", "sofia" + DOMINIO, "97050000101", "SECRETARIA_ADMIN", true, hasher);
        salvarUsuario(MEDICO, "Marcos Acesso", "marcos" + DOMINIO, "97050000102", "MEDICO", true, hasher);
        salvarUsuario(PACIENTE, "Paula Acesso", "paula" + DOMINIO, "97050000103", "PACIENTE", true, hasher);
        salvarUsuario(INATIVO, "Igor Acesso", "igor" + DOMINIO, "97050000104", "SECRETARIA_ADMIN", false, hasher);

        relogio = new RelogioAjustavel(AGORA);
        var sessoes = new Sessoes(Duration.ofMinutes(60), relogio);
        var autenticador = new Autenticador(sessoes);
        var auth = new AuthHandler(new AuthService(new UsuarioRepository.Mongo(db), hasher, sessoes, relogio), autenticador);
        var pacientes = new PacienteHandler(new PacienteService(new PacienteRepository.Mongo(db), new PacienteValidator(),
                cpf -> validadorCpf.get().validar(cpf)));

        // Mesma montagem do App.java: /pacientes tem contexto próprio (caminhos com ID) e as
        // permissões vêm de Rotas.pacientes. Rotas fora deste fluxo ficam sem handler.
        var cors = new Cors(List.of());
        var autorizacao = new Autorizacao(autenticador);
        var router = Rotas.router(cors, autorizacao, new Rotas.Handlers(
                new HealthHandler(), auth, null, null, pacientes, null, null, null, null, null, null));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Várias threads, como um servidor real: permite pedidos simultâneos no teste de corrida.
        threadsHttp = Executors.newFixedThreadPool(8);
        server.setExecutor(threadsHttp);
        server.createContext("/", router);
        server.createContext("/pacientes", Rotas.pacientes(cors, autorizacao, pacientes));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach void encerrar() {
        if (client != null) client.close();
        if (server != null) server.stop(0);
        if (threadsHttp != null) threadsHttp.shutdownNow();
        limpar();
    }

    // =================================================================== login e sessão

    @Nested class LoginComUsuarioDoMongo {
        @Test void abreSessaoDevolveOPerfilERegistraOAcesso() throws Exception {
            var r = login("sofia" + DOMINIO, SENHA);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode corpo = json.readTree(r.body());
            assertFalse(corpo.get("token").asText().isBlank());
            assertEquals(AGORA.plus(Duration.ofMinutes(60)).toString(), corpo.get("expiraEm").asText());
            assertEquals("Sofia Acesso", corpo.get("usuario").get("nome").asText());
            assertEquals("SECRETARIA_ADMIN", corpo.get("usuario").get("perfil").asText());
            assertEquals(SECRETARIA.toHexString(), corpo.get("usuario").get("id").asText());

            Document gravado = db.getCollection("usuarios").find(Filters.eq("_id", SECRETARIA)).first();
            assertEquals(Date.from(AGORA), gravado.getDate("ultimoAcessoEm"), "o login grava o último acesso no Mongo");
        }

        @Test void meDevolveOUsuarioDaSessao() throws Exception {
            var me = enviar("GET", "/auth/me", token("marcos"), null);
            assertEquals(200, me.statusCode(), me.body());
            assertEquals("MEDICO", json.readTree(me.body()).get("perfil").asText());
        }

        @Test void emailComMaiusculasEEspacosEncontraOUsuario() throws Exception {
            assertEquals(200, login("  PAULA" + DOMINIO.toUpperCase() + " ", SENHA).statusCode());
        }

        @Test void senhaErradaEEmailInexistenteRetornam401() throws Exception {
            assertEquals(AuthService.SENHA_INCORRETA, erro(login("sofia" + DOMINIO, "errada"), 401));
            assertEquals(AuthService.EMAIL_NAO_CADASTRADO, erro(login("ninguem" + DOMINIO, SENHA), 401));
        }

        @Test void usuarioInativoNaoEntraENaoRegistraAcesso() throws Exception {
            assertEquals(AuthService.USUARIO_INATIVO, erro(login("igor" + DOMINIO, SENHA), 401));
            assertNull(db.getCollection("usuarios").find(Filters.eq("_id", INATIVO)).first().getDate("ultimoAcessoEm"));
        }

        @Test void cadaLoginAbreUmaSessaoIndependente() throws Exception {
            String primeiro = token("sofia");
            String segundo = token("sofia");
            assertNotEquals(primeiro, segundo);
            assertEquals(204, enviar("POST", "/auth/logout", primeiro, null).statusCode());
            assertEquals(401, enviar("GET", "/pacientes", primeiro, null).statusCode());
            assertEquals(200, enviar("GET", "/pacientes", segundo, null).statusCode(), "o outro login continua válido");
        }

        @Test void logoutEncerraOAcessoAoCadastroDePacientes() throws Exception {
            String sessao = token("sofia");
            assertEquals(200, enviar("GET", "/pacientes", sessao, null).statusCode());
            assertEquals(204, enviar("POST", "/auth/logout", sessao, null).statusCode());
            assertEquals(401, enviar("GET", "/pacientes", sessao, null).statusCode());
            assertEquals(401, enviar("POST", "/pacientes", sessao, paciente("Ana Lima", CPF_ANA, "ana")).statusCode());
            assertEquals(0, pacientesDoTeste(), "nada é gravado com o token encerrado");
        }

        @Test void sessaoExpiradaNoMeioDoCadastroExigeNovoLogin() throws Exception {
            String sessao = token("sofia");
            assertEquals(201, enviar("POST", "/pacientes", sessao, paciente("Ana Lima", CPF_ANA, "ana")).statusCode());

            relogio.avancar(Duration.ofMinutes(61));

            var recusado = enviar("POST", "/pacientes", sessao, paciente("Bruno Lima", CPF_BRUNO, "bruno"));
            assertEquals(401, recusado.statusCode());
            assertEquals(1, pacientesDoTeste(), "o pedido com sessão vencida não grava nada");
            assertEquals(201, enviar("POST", "/pacientes", token("sofia"), paciente("Bruno Lima", CPF_BRUNO, "bruno"))
                    .statusCode(), "um novo login continua de onde parou");
        }

        @Test void sessaoAindaValidaUmMinutoAntesDeExpirar() throws Exception {
            String sessao = token("marcos");
            relogio.avancar(Duration.ofMinutes(59));
            assertEquals(200, enviar("GET", "/pacientes", sessao, null).statusCode());
        }
    }

    // =================================================================== CRUD de pacientes

    @Nested class CadastroDePacientesNoMongo {
        @Test void secretariaCadastraEOMongoGuardaOsDadosNormalizados() throws Exception {
            var r = enviar("POST", "/pacientes", token("sofia"),
                    Map.of("nome", "  Ana Lima ", "cpf", mascarado(CPF_ANA), "email", "ANA" + DOMINIO.toUpperCase()));
            assertEquals(201, r.statusCode(), r.body());
            JsonNode criado = json.readTree(r.body());
            String id = criado.get("id").asText();
            assertEquals("/pacientes/" + id, r.headers().firstValue("Location").orElseThrow());

            Document gravado = db.getCollection("pacientes").find(Filters.eq("_id", new ObjectId(id))).first();
            assertNotNull(gravado, "o paciente está no MongoDB, não só na resposta");
            assertEquals("Ana Lima", gravado.getString("nome"));
            assertEquals(CPF_ANA, gravado.getString("cpf"), "CPF sem máscara, como o validador do banco exige");
            assertEquals("ana" + DOMINIO, gravado.getString("email"), "e-mail em minúsculas");
        }

        @Test void listaBuscaDetalhaEEditaPeloMongo() throws Exception {
            String sofia = token("sofia");
            String idAna = criar(sofia, "Ana Lima", CPF_ANA, "ana");
            criar(sofia, "Bruno Souza", CPF_BRUNO, "bruno");

            JsonNode todos = json.readTree(enviar("GET", "/pacientes", sofia, null).body());
            List<String> nomes = nomesDoTeste(todos);
            assertEquals(List.of("Ana Lima", "Bruno Souza"), nomes, "ordenado por nome");

            for (String busca : List.of("ana%20l", mascarado(CPF_ANA), CPF_ANA.substring(0, 9), "ANA%40ACESSO")) {
                JsonNode achados = json.readTree(enviar("GET", "/pacientes?busca=" + busca, sofia, null).body());
                assertEquals(List.of("Ana Lima"), nomesDoTeste(achados), "busca: " + busca);
            }

            var detalhe = enviar("GET", "/pacientes/" + idAna, sofia, null);
            assertEquals(200, detalhe.statusCode());
            assertEquals(CPF_ANA, json.readTree(detalhe.body()).get("cpf").asText());

            var editado = enviar("PUT", "/pacientes/" + idAna, sofia, paciente("Ana Lima Prado", CPF_ANA, "ana.prado"));
            assertEquals(200, editado.statusCode(), editado.body());
            Document gravado = db.getCollection("pacientes").find(Filters.eq("_id", new ObjectId(idAna))).first();
            assertEquals("Ana Lima Prado", gravado.getString("nome"));
            assertEquals("ana.prado" + DOMINIO, gravado.getString("email"));
            assertEquals(2, pacientesDoTeste(), "a edição não cria um registro novo");
        }

        @Test void buscaComCaracteresDeRegexETratadaComoTexto() throws Exception {
            String sofia = token("sofia");
            criar(sofia, "Ana Lima", CPF_ANA, "ana");
            for (String busca : List.of(".%2A", "%5E", "(")) {
                var r = enviar("GET", "/pacientes?busca=" + busca, sofia, null);
                assertEquals(200, r.statusCode(), "busca: " + busca);
                assertEquals(List.of(), nomesDoTeste(json.readTree(r.body())), "busca: " + busca);
            }
        }

        @Test void cpfOuEmailRepetidoNaoGravaSegundoRegistro() throws Exception {
            String sofia = token("sofia");
            criar(sofia, "Ana Lima", CPF_ANA, "ana");

            var mesmoCpf = enviar("POST", "/pacientes", sofia, paciente("Outra Ana", mascarado(CPF_ANA), "outra"));
            var mesmoEmail = enviar("POST", "/pacientes", sofia, paciente("Outra Ana", CPF_BRUNO, "ANA"));
            assertErro(mesmoCpf, "CPF já cadastrado para outro paciente.");
            assertErro(mesmoEmail, "E-mail já cadastrado para outro paciente.");
            assertEquals(1, pacientesDoTeste());
        }

        @Test void edicaoParaCpfDeOutroPacienteERecusada() throws Exception {
            String sofia = token("sofia");
            criar(sofia, "Ana Lima", CPF_ANA, "ana");
            String idBruno = criar(sofia, "Bruno Souza", CPF_BRUNO, "bruno");

            assertErro(enviar("PUT", "/pacientes/" + idBruno, sofia, paciente("Bruno Souza", CPF_ANA, "bruno")),
                    "CPF já cadastrado para outro paciente.");
            Document bruno = db.getCollection("pacientes").find(Filters.eq("_id", new ObjectId(idBruno))).first();
            assertEquals(CPF_BRUNO, bruno.getString("cpf"), "o registro continua com o CPF original");
        }

        /** Formato e dígitos repetidos são barrados pelo PacienteValidator, antes do servidor de CPF. */
        @ParameterizedTest
        @ValueSource(strings = {"00000000000", "123", "529.982.247", "abc.def.ghi-jk"})
        void cpfMalFormadoNaoChegaAoBanco(String cpf) throws Exception {
            var r = enviar("POST", "/pacientes", token("sofia"), paciente("Ana Lima", cpf, "ana"));
            assertEquals(400, r.statusCode(), r.body());
            assertEquals(0, pacientesDoTeste());
        }

        /**
         * Os dígitos verificadores são conferidos pelo servidor TCP de CPF (módulo server/), como no
         * App.java. Requer o servidor na porta 3000, iniciado pelo CI antes dos testes do backend.
         */
        @Test void digitoVerificadorErradoERecusadoPeloServidorDeCpf() throws Exception {
            validadorCpf.set(new ValidadorCpfTcp("localhost", 3000));
            String sofia = token("sofia");
            String errado = CPF_ANA.substring(0, 10) + ((CPF_ANA.charAt(10) - '0' + 1) % 10);

            var recusado = enviar("POST", "/pacientes", sofia, paciente("Ana Lima", errado, "ana"));
            assertEquals(400, recusado.statusCode(), recusado.body());
            assertEquals("CPF inválido.", mensagemDeErro(recusado));
            assertEquals(0, pacientesDoTeste());

            assertEquals(201, enviar("POST", "/pacientes", sofia, paciente("Ana Lima", CPF_ANA, "ana")).statusCode(),
                    "o mesmo cadastro com o CPF correto passa pelo servidor e chega ao Mongo");
            assertEquals(1, pacientesDoTeste());
        }

        @Test void idInexistenteRetorna404() throws Exception {
            String sofia = token("sofia");
            String ausente = new ObjectId().toHexString();
            assertEquals(404, enviar("GET", "/pacientes/" + ausente, sofia, null).statusCode());
            assertEquals(404, enviar("PUT", "/pacientes/" + ausente, sofia, paciente("Ana Lima", CPF_ANA, "ana")).statusCode());
            assertEquals(0, pacientesDoTeste(), "PUT em ID ausente não cria paciente (sem upsert)");
        }

        @Test void validadorDeCpfIndisponivelRetorna503SemGravar() throws Exception {
            validadorCpf.set(cpf -> { throw new ServidorCpfIndisponivelException(new ConnectException("Connection refused")); });
            var r = enviar("POST", "/pacientes", token("sofia"), paciente("Ana Lima", CPF_ANA, "ana"));
            assertEquals(503, r.statusCode(), r.body());
            assertEquals(0, pacientesDoTeste());

            validadorCpf.set(cpf -> {});
            assertEquals(201, enviar("POST", "/pacientes", token("sofia"), paciente("Ana Lima", CPF_ANA, "ana")).statusCode(),
                    "quando o validador volta, o mesmo cadastro passa");
        }
    }

    // =================================================================== apoio

    private void limpar() {
        db.getCollection("usuarios").deleteMany(Filters.in("_id", USUARIOS));
        db.getCollection("pacientes").deleteMany(Filters.regex("email", "@acesso\\.consultafacil\\.test$"));
    }

    private void salvarUsuario(ObjectId id, String nome, String email, String cpf, String perfil, boolean ativo,
                               SenhaHasher hasher) {
        SenhaHash senha = hasher.gerar(SENHA);
        Date criado = Date.from(AGORA.minus(Duration.ofDays(30)));
        // Passa pelo validador $jsonSchema da coleção usuarios (iteracoes precisa ser int).
        db.getCollection("usuarios").insertOne(new Document("_id", id).append("nome", nome).append("cpf", cpf)
                .append("email", email)
                .append("senha", new Document("hash", senha.hash()).append("salt", senha.salt())
                        .append("algoritmo", senha.algoritmo()).append("iteracoes", senha.iteracoes()))
                .append("perfil", perfil).append("ativo", ativo).append("criadoEm", criado).append("atualizadoEm", criado));
    }

    private long pacientesDoTeste() {
        return db.getCollection("pacientes").countDocuments(Filters.regex("email", "@acesso\\.consultafacil\\.test$"));
    }

    /** Nomes dos pacientes deste teste na resposta, ignorando registros de outros testes no mesmo banco. */
    private static List<String> nomesDoTeste(JsonNode lista) {
        var nomes = new ArrayList<String>();
        for (JsonNode p : lista) if (p.get("email").asText().endsWith(DOMINIO)) nomes.add(p.get("nome").asText());
        return nomes;
    }

    private String criar(String token, String nome, String cpf, String usuarioEmail) throws Exception {
        var r = enviar("POST", "/pacientes", token, paciente(nome, cpf, usuarioEmail));
        assertEquals(201, r.statusCode(), r.body());
        return json.readTree(r.body()).get("id").asText();
    }

    private static Map<String, String> paciente(String nome, String cpf, String usuarioEmail) {
        return Map.of("nome", nome, "cpf", cpf, "email", usuarioEmail + DOMINIO);
    }

    private String token(String usuario) throws Exception {
        var r = login(usuario + DOMINIO, SENHA);
        assertEquals(200, r.statusCode(), r.body());
        return json.readTree(r.body()).get("token").asText();
    }

    private HttpResponse<String> login(String email, String senha) throws Exception {
        return enviar("POST", "/auth/login", null, Map.of("email", email, "senha", senha));
    }

    private String erro(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return json.readTree(r.body()).get("erro").asText();
    }

    /**
     * Duplicidade: o serviço consulta o Mongo antes de gravar e responde 400 com a mensagem do campo
     * repetido; o índice único (erro 11000) fica como proteção para pedidos simultâneos.
     */
    private void assertErro(HttpResponse<String> r, String mensagem) throws Exception {
        assertEquals(400, r.statusCode(), r.body());
        assertEquals(mensagem, mensagemDeErro(r));
    }

    /**
     * O cadastro de pacientes responde {@code {"mensagem": ...}} e a autorização {@code {"erro": ...}}
     * (docs/api/pacientes.md, seção Erros). O front lê os dois; o teste também.
     */
    private String mensagemDeErro(HttpResponse<String> r) throws Exception {
        JsonNode corpo = json.readTree(r.body());
        JsonNode mensagem = corpo.has("mensagem") ? corpo.get("mensagem") : corpo.get("erro");
        assertNotNull(mensagem, "resposta sem mensagem de erro: " + r.body());
        return mensagem.asText();
    }

    private HttpResponse<String> enviar(String metodo, String rota, String token, Map<String, ?> corpo) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + rota)).timeout(Duration.ofSeconds(10));
        if (token != null) b.header("Authorization", "Bearer " + token);
        var publicador = corpo == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo));
        if (corpo != null) b.header("Content-Type", "application/json");
        return client.send(b.method(metodo, publicador).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String mascarado(String cpf) {
        return cpf.substring(0, 3) + "." + cpf.substring(3, 6) + "." + cpf.substring(6, 9) + "-" + cpf.substring(9);
    }

    /** Completa 9 dígitos com os dois verificadores do CPF. */
    private static String cpfValido(String nove) {
        String cpf = nove;
        for (int tamanho = 9; tamanho <= 10; tamanho++) {
            int soma = 0;
            for (int i = 0; i < tamanho; i++) soma += (cpf.charAt(i) - '0') * (tamanho + 1 - i);
            int resto = soma % 11;
            cpf += resto < 2 ? 0 : 11 - resto;
        }
        return cpf;
    }
}
