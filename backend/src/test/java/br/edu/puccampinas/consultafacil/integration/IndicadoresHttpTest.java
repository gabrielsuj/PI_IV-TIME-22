package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.*;
import br.edu.puccampinas.consultafacil.api.handler.IndicadoresHandler;
import br.edu.puccampinas.consultafacil.api.middleware.Cors;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.repository.IndicadoresLeituraRepository;
import br.edu.puccampinas.consultafacil.repository.IndicadoresMongoRepository;
import br.edu.puccampinas.consultafacil.support.FontesIndicadoresEmMemoria;
import br.edu.puccampinas.consultafacil.repository.IndicadoresLeituraRepository.*;
import br.edu.puccampinas.consultafacil.repository.MedicoDoUsuarioRepository;
import br.edu.puccampinas.consultafacil.security.*;
import br.edu.puccampinas.consultafacil.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoDatabase;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bson.types.ObjectId;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class IndicadoresHttpTest {
    private static final ObjectId MEDICO = new ObjectId(), OUTRO_MEDICO = new ObjectId();
    private static final UsuarioLogado SECRETARIA = usuario(Perfil.SECRETARIA_ADMIN), MEDICO_LOGADO = usuario(Perfil.MEDICO), PACIENTE = usuario(Perfil.PACIENTE);
    private static final String CAMINHO = "/indicadores?de=2026-11-20&ate=2026-11-20";
    private final Sessoes sessoes = new Sessoes(Duration.ofMinutes(60));
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger leituras = new AtomicInteger();
    private final List<Optional<ObjectId>> escopos = new CopyOnWriteArrayList<>();
    private final List<PeriodoIndicadores> periodos = new CopyOnWriteArrayList<>();
    private volatile IndicadoresLeituraRepository fonte = (periodo, medico) -> fixture();
    private IndicadoresService service;
    private HttpServer servidor;
    private HttpClient cliente;
    private ExecutorService executor;
    private String base;

    private static Resumo fixture() {
        return new Resumo(new Consultas(1, 1, 2, 1, 1), new Capacidade(8, 6, 0),
                new Confirmacao(4, 0, 0), new Fila(2, 0));
    }

    @BeforeEach void iniciar() throws Exception {
        MongoDatabase semBanco = (MongoDatabase) Proxy.newProxyInstance(MongoDatabase.class.getClassLoader(),
                new Class<?>[]{MongoDatabase.class}, (proxy, metodo, argumentos) -> {
                    if ("getCollection".equals(metodo.getName())) return null;
                    throw new AssertionError("Não acessar MongoDB neste teste");
                });
        var resolvedor = new MedicoDoUsuarioRepository(semBanco) {
            @Override public Optional<ObjectId> buscar(String usuarioId) {
                return MEDICO_LOGADO.id().equals(usuarioId) ? Optional.of(MEDICO) : Optional.empty();
            }
        };
        service = new IndicadoresService((periodo, medico) -> {
            leituras.incrementAndGet();
            periodos.add(periodo);
            escopos.add(medico);
            return fonte.ler(periodo, medico);
        }, new AcessoAgenda(resolvedor));
        HttpHandler eco = ex -> Http.sendJson(ex, 200, Map.of("rota", "existente"));
        var cors = new Cors(List.of("http://localhost:5173"));
        var autz = new Autorizacao(new Autenticador(sessoes));
        var handlers = new Rotas.Handlers(eco, eco, eco, eco, eco, eco, eco, eco, eco, eco, new IndicadoresHandler(service));
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        servidor.setExecutor(executor);
        servidor.createContext("/", Rotas.router(cors, autz, handlers));
        servidor.createContext("/consultas/", Rotas.consultaPorId(cors, autz, eco, eco, eco, eco));
        servidor.start();
        base = "http://127.0.0.1:" + servidor.getAddress().getPort();
        cliente = HttpClient.newHttpClient();
    }

    @AfterEach void encerrar() {
        if (cliente != null) cliente.close();
        if (servidor != null) servidor.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test void fixtureManualRetornaCincoMetricasContagensEFusoSemDadosPessoais() throws Exception {
        var resposta = enviar("GET", CAMINHO, token(SECRETARIA));
        assertEquals(200, resposta.statusCode(), resposta.body());
        var corpo = json.readTree(resposta.body());
        assertEquals("2026-11-20", corpo.get("de").asText());
        assertEquals("2026-11-20", corpo.get("ate").asText());
        assertEquals("America/Sao_Paulo", corpo.get("fusoHorario").asText());
        assertTrue(corpo.has("medicoId"));
        assertTrue(corpo.get("medicoId").isNull());
        assertEquals(6, corpo.get("contagens").get("totalConsultas").asInt());
        assertEquals(5, corpo.get("contagens").get("consultasNaoCanceladas").asInt());
        assertEquals(3, corpo.get("contagens").get("consultasComDesfecho").asInt());
        conferirTaxa(corpo.get("ocupacao"), 6, 8, "75.00");
        conferirTaxa(corpo.get("faltas"), 1, 3, "33.33");
        conferirTaxa(corpo.get("cancelamento"), 1, 6, "16.67");
        conferirTaxa(corpo.get("confirmacao"), 4, 6, "66.67");
        conferirTaxa(corpo.get("preenchimentoFila"), 2, 5, "40.00");
        assertTrue(corpo.get("qualidade").get("capacidadeEstimada").asBoolean());
        assertTrue(corpo.get("qualidade").get("observacaoCapacidade").asText().contains("agenda atual"));
        for (String proibido : List.of("pacienteId", "pacienteNome", "senha", "eventosPendentes", "consultaAgendadaId", "versao", "$oid"))
            assertFalse(resposta.body().contains(proibido), resposta.body());
        assertEquals(List.of(Optional.empty()), escopos);
        assertEquals(1, leituras.get());
        assertEquals("2026-11-20T03:00:00Z", periodos.getFirst().inicioInclusivo().toString());
        assertEquals("2026-11-21T03:00:00Z", periodos.getFirst().fimExclusivo().toString());
    }

    @Test void endpointConsolidaFontesEmMemoriaComAsCalculadorasReais() throws Exception {
        var f = FontesIndicadoresEmMemoria.fixture();
        f.medicos.clear(); f.medicos.add(MEDICO);
        f.consultas.forEach(c -> c.put("medicoId", MEDICO));
        f.agendas.getFirst().put("medicoId", MEDICO);
        f.bloqueios.put(MEDICO, f.bloqueios.remove(FontesIndicadoresEmMemoria.MEDICO));
        fonte = new IndicadoresMongoRepository(f);
        var resposta = enviar("GET", "/indicadores?de=2026-11-23&ate=2026-11-23", token(MEDICO_LOGADO));
        assertEquals(200, resposta.statusCode(), resposta.body());
        var corpo = json.readTree(resposta.body());
        conferirTaxa(corpo.get("ocupacao"), 6, 8, "75.00");
        conferirTaxa(corpo.get("faltas"), 1, 3, "33.33");
        conferirTaxa(corpo.get("cancelamento"), 1, 6, "16.67");
        conferirTaxa(corpo.get("confirmacao"), 4, 6, "66.67");
        conferirTaxa(corpo.get("preenchimentoFila"), 2, 5, "40.00");
        assertEquals(MEDICO.toHexString(), corpo.get("medicoId").asText());
    }

    @ParameterizedTest
    @CsvSource({"pendencia,confirmacao,DADOS_PENDENTES", "legado,confirmacao,DADOS_INCOMPLETOS",
            "confirmacaoAusente,confirmacao,DADOS_INCOMPLETOS", "orfao,preenchimentoFila,DADOS_INCOMPLETOS",
            "semAgenda,ocupacao,DADOS_INCOMPLETOS"})
    void qualidadeDasFontesCompletasChegaAoJsonSemPercentualFalso(String defeito, String metrica, String situacao) throws Exception {
        var f = FontesIndicadoresEmMemoria.fixture();
        switch (defeito) {
            case "pendencia" -> f.consultas.getFirst().put("eventosPendentes", List.of(new Document("_id", new ObjectId())));
            case "legado" -> f.eventos.clear();
            case "confirmacaoAusente" -> f.eventos.removeIf(e -> "CONFIRMACAO".equals(e.getString("evento")));
            case "orfao" -> f.fila.add(FontesIndicadoresEmMemoria.atendida(new ObjectId()));
            case "semAgenda" -> f.agendas.clear();
            default -> throw new AssertionError(defeito);
        }
        String antes = new Document("consultas", f.consultas).append("historico", f.eventos).append("fila", f.fila).toJson();
        fonte = new IndicadoresMongoRepository(f);
        var resposta = enviar("GET", "/indicadores?de=2026-11-23&ate=2026-11-23", token(SECRETARIA));
        assertEquals(200, resposta.statusCode(), resposta.body());
        var corpo = json.readTree(resposta.body());
        assertEquals(situacao, corpo.get(metrica).get("situacao").asText());
        assertTrue(corpo.get(metrica).get("percentual").isNull());
        assertEquals(6, corpo.get("contagens").get("totalConsultas").asInt());
        conferirTaxa(corpo.get("faltas"), 1, 3, "33.33");
        assertEquals(antes, new Document("consultas", f.consultas).append("historico", f.eventos).append("fila", f.fila).toJson());
    }

    @Test void mudancaDuranteLeituraRetorna503EOutraRequisicaoPodeLerDadosEstaveis() throws Exception {
        var f = new FontesIndicadoresEmMemoria() {
            @Override public List<Document> historico(Set<ObjectId> ids) {
                consultas.getFirst().put("status", "CANCELADA");
                return super.historico(ids);
            }
        };
        var dados = FontesIndicadoresEmMemoria.fixture();
        f.medicos.addAll(dados.medicos); f.consultas.addAll(dados.consultas); f.agendas.addAll(dados.agendas);
        f.bloqueios.putAll(dados.bloqueios); f.eventos.addAll(dados.eventos); f.fila.addAll(dados.fila);
        fonte = new IndicadoresMongoRepository(f);
        String caminho = "/indicadores?de=2026-11-23&ate=2026-11-23";
        String sessao = token(SECRETARIA);
        var resposta = enviar("GET", caminho, sessao);
        conferirErro(resposta, 503);
        assertFalse(resposta.body().contains("contagens"));
        assertFalse(resposta.body().contains(f.consultas.getFirst().getObjectId("_id").toHexString()));
        assertEquals(1, leituras.get());
        var recuperada = enviar("GET", caminho, sessao);
        assertEquals(200, recuperada.statusCode(), recuperada.body());
        assertEquals(2, json.readTree(recuperada.body()).get("contagens").get("canceladas").asInt());
        assertEquals(2, leituras.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "&medicoId=meu", "&medicoId=proprio"})
    void medicoSoConsultaCadastroResolvidoPeloUsuarioId(String filtro) throws Exception {
        if (filtro.endsWith("proprio")) filtro = "&medicoId=" + MEDICO.toHexString().toUpperCase(Locale.ROOT);
        var resposta = enviar("GET", CAMINHO + filtro, token(MEDICO_LOGADO));
        assertEquals(200, resposta.statusCode(), resposta.body());
        assertNotEquals(MEDICO_LOGADO.id(), MEDICO.toHexString());
        assertEquals(MEDICO.toHexString(), json.readTree(resposta.body()).get("medicoId").asText());
        assertEquals(List.of(Optional.of(MEDICO)), escopos);
    }

    @Test void secretariaPodeFiltrarOutroMedico() throws Exception {
        var resposta = enviar("GET", CAMINHO + "&medicoId=" + OUTRO_MEDICO, token(SECRETARIA));
        assertEquals(200, resposta.statusCode(), resposta.body());
        assertEquals(OUTRO_MEDICO.toHexString(), json.readTree(resposta.body()).get("medicoId").asText());
        assertEquals(List.of(Optional.of(OUTRO_MEDICO)), escopos);
    }

    @Test void pacienteOutroMedicoELoginSemVinculoSaoRecusadosAntesDaFonte() throws Exception {
        conferirErro(enviar("GET", CAMINHO, token(PACIENTE)), 403);
        conferirErro(enviar("GET", CAMINHO + "&medicoId=" + OUTRO_MEDICO, token(MEDICO_LOGADO)), 403);
        conferirErro(enviar("GET", CAMINHO + "&medicoId=" + MEDICO_LOGADO.id(), token(MEDICO_LOGADO)), 403);
        conferirErro(enviar("GET", CAMINHO, token(usuario(Perfil.MEDICO))), 403);
        assertEquals(0, leituras.get());
    }

    @Test void sessaoAusenteInvalidaOuEncerradaNaoAcessaFonte() throws Exception {
        conferirErro(enviar("GET", CAMINHO, null), 401);
        conferirErro(enviar("GET", CAMINHO, "invalido"), 401);
        String token = token(SECRETARIA);
        sessoes.encerrar(token);
        conferirErro(enviar("GET", CAMINHO, token), 401);
        assertEquals(0, leituras.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?de=2026-11-20", "?ate=2026-11-20", "?de=&ate=2026-11-20",
            "?de=2026-02-30&ate=2026-03-01", "?de=2026-11-21&ate=2026-11-20", "?de=2026-01-01&ate=2026-03-04",
            "?de=2026-1-01&ate=2026-01-02", "?de=2026-11-20T00:00:00Z&ate=2026-11-20",
            "?de=2026-11-20&ate=2026-11-20&limite=100", "?de=2026-11-20&ate=2026-11-20&de=2026-11-21",
            "?de=2026-11-20&ate=2026-11-20&%64e=2026-11-20", "?de=2026-11-20&&ate=2026-11-20",
            "?de=2026-11-20&ate=2026-11-20&", "?de=2026-11-20&ate=2026-11-20&medicoId=",
            "?de=2026-11-20&ate=2026-11-20&medicoId=meu", "?de=2026-11-20&ate=2026-11-20&medicoId=invalido"})
    void parametrosInvalidosNaoChegamAFonte(String query) throws Exception {
        conferirErro(enviar("GET", "/indicadores" + query, token(SECRETARIA)), 400);
        assertEquals(0, leituras.get());
    }

    @Test void limiteSessentaEDoisDiasEhInclusivo() throws Exception {
        assertEquals(200, enviar("GET", "/indicadores?de=2026-01-01&ate=2026-03-03", token(SECRETARIA)).statusCode());
        assertEquals(1, leituras.get());
    }

    @Test void filtroDeCadastroInexistentePreserva404DaFonte() throws Exception {
        fonte = (periodo, medico) -> { throw ApiException.notFound("Médico não encontrado"); };
        conferirErro(enviar("GET", CAMINHO + "&medicoId=" + new ObjectId(), token(SECRETARIA)), 404);
        assertEquals(1, leituras.get());
    }

    @Test void fonteIndisponivelRetorna503SemNumerosSimulados() throws Exception {
        fonte = (periodo, medico) -> { throw new ApiException(503, "Indicadores temporariamente indisponíveis."); };
        conferirErro(enviar("GET", CAMINHO, token(SECRETARIA)), 503);
        assertEquals(1, leituras.get());
    }

    @Test void falhaDaFonteNaoViraIndicadoresVaziosNemExpoeMensagemInterna() throws Exception {
        fonte = (periodo, medico) -> { throw new IllegalStateException("detalhe-interno-restrito"); };
        var resposta = enviar("GET", CAMINHO, token(SECRETARIA));
        conferirErro(resposta, 500);
        assertFalse(resposta.body().contains("detalhe-interno-restrito"));
        assertEquals(1, leituras.get());
    }

    @Test void periodoSemDadosExplicitaPercentualNuloEmTodasAsTaxas() throws Exception {
        fonte = (periodo, medico) -> new Resumo(new Consultas(0, 0, 0, 0, 0), new Capacidade(0, 0, 0), new Confirmacao(0, 0, 0), new Fila(0, 0));
        var resposta = enviar("GET", CAMINHO, token(SECRETARIA));
        assertEquals(200, resposta.statusCode());
        var corpo = json.readTree(resposta.body());
        for (String nome : List.of("ocupacao", "faltas", "cancelamento", "confirmacao", "preenchimentoFila")) {
            assertTrue(corpo.get(nome).has("percentual"));
            assertTrue(corpo.get(nome).get("percentual").isNull());
            assertEquals("SEM_BASE", corpo.get(nome).get("situacao").asText());
        }
        Map<String, Object> outroContrato = new HashMap<>();
        outroContrato.put("nulo", null);
        assertEquals("{}", Json.toJson(outroContrato));
    }

    @ParameterizedTest
    @CsvSource({"projecao,confirmacao,DADOS_PENDENTES", "legado,confirmacao,DADOS_INCOMPLETOS",
            "agenda,ocupacao,DADOS_INCOMPLETOS", "fila,preenchimentoFila,DADOS_INCOMPLETOS"})
    void qualidadeImpedePercentualEnganosoSemApagarOutrasMetricas(String caso, String metrica, String situacao) throws Exception {
        var original = fixture();
        fonte = (periodo, medico) -> switch (caso) {
            case "projecao" -> new Resumo(original.consultas(), original.capacidade(), new Confirmacao(0, 1, 1), original.fila());
            case "legado" -> new Resumo(original.consultas(), original.capacidade(), new Confirmacao(2, 0, 1), original.fila());
            case "agenda" -> new Resumo(original.consultas(), new Capacidade(8, 6, 1), original.confirmacao(), original.fila());
            default -> new Resumo(original.consultas(), original.capacidade(), original.confirmacao(), new Fila(1, 1));
        };
        var resposta = enviar("GET", CAMINHO, token(SECRETARIA));
        assertEquals(200, resposta.statusCode(), resposta.body());
        var corpo = json.readTree(resposta.body());
        assertTrue(corpo.get(metrica).get("percentual").isNull());
        assertEquals(situacao, corpo.get(metrica).get("situacao").asText());
        conferirTaxa(corpo.get("faltas"), 1, 3, "33.33");
        assertEquals(6, corpo.get("contagens").get("totalConsultas").asInt());
    }

    @Test void servicoTambemRecusaAtorInvalidoOuPerfilNaoAutorizado() {
        for (UsuarioLogado ator : Arrays.asList(null, new UsuarioLogado("invalido", "Teste", Perfil.MEDICO),
                new UsuarioLogado(SECRETARIA.id(), "Teste", null)))
            assertEquals(401, assertThrows(ApiException.class, () -> service.consultar("2026-11-20", "2026-11-20", null, ator)).status());
        assertEquals(403, assertThrows(ApiException.class, () -> service.consultar("2026-11-20", "2026-11-20", null, PACIENTE)).status());
        assertEquals(0, leituras.get());
    }

    @Test void contagensInconsistentesNaoSaoConvertidasEmPercentuaisPublicos() {
        fonte = (periodo, medico) -> new Resumo(new Consultas(1, 0, 0, 0, 0), new Capacidade(0, 0, 0), new Confirmacao(0, 2, 0), new Fila(0, 0));
        assertThrows(IllegalStateException.class, () -> service.consultar("2026-11-20", "2026-11-20", null, SECRETARIA));
        fonte = (periodo, medico) -> null;
        assertThrows(NullPointerException.class, () -> service.consultar("2026-11-20", "2026-11-20", null, SECRETARIA));
    }

    @Test void corsPreflightNaoExigeSessaoEOutrasRotasContinuamRegistradas() throws Exception {
        var preflight = enviar("OPTIONS", "/indicadores", null);
        assertEquals(204, preflight.statusCode());
        assertEquals("http://localhost:5173", preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        String token = token(SECRETARIA);
        for (String rota : List.of("/consultas", "/consultas/lista", "/consultas/abc", "/consultas/abc/historico"))
            assertEquals(200, enviar("GET", rota, token).statusCode());
        for (String rota : List.of("/consultas", "/consultas/abc/status", "/consultas/abc/cancelamento"))
            assertEquals(200, enviar("POST", rota, token).statusCode());
        assertEquals(404, enviar("POST", "/indicadores", token).statusCode());
        assertEquals(404, enviar("GET", "/indicadores/extra", token).statusCode());
        assertEquals(0, leituras.get());
    }

    private String token(UsuarioLogado usuario) { return sessoes.criar(usuario).token(); }
    private static UsuarioLogado usuario(Perfil perfil) { return new UsuarioLogado(new ObjectId().toHexString(), "Teste", perfil); }
    private HttpResponse<String> enviar(String metodo, String caminho, String token) throws Exception {
        var pedido = HttpRequest.newBuilder(URI.create(base + caminho)).header("Origin", "http://localhost:5173")
                .method(metodo, HttpRequest.BodyPublishers.noBody());
        if (token != null) pedido.header("Authorization", "Bearer " + token);
        return cliente.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void conferirErro(HttpResponse<String> resposta, int esperado) throws Exception {
        assertEquals(esperado, resposta.statusCode(), resposta.body());
        var corpo = json.readTree(resposta.body());
        assertEquals(esperado, corpo.get("status").asInt());
        assertFalse(corpo.get("erro").asText().isBlank());
        assertFalse(corpo.has("contagens"));
    }

    private static void conferirTaxa(JsonNode taxa, long numerador, long denominador, String percentual) {
        assertEquals(numerador, taxa.get("numerador").asLong());
        assertEquals(denominador, taxa.get("denominador").asLong());
        assertEquals(0, new BigDecimal(percentual).compareTo(taxa.get("percentual").decimalValue()));
        assertEquals("DISPONIVEL", taxa.get("situacao").asText());
    }
}
