package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.Rotas;
import br.edu.puccampinas.consultafacil.api.handler.StatusConsultaHandler;
import br.edu.puccampinas.consultafacil.api.middleware.Cors;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.repository.ConsultaRepository;
import br.edu.puccampinas.consultafacil.security.Autenticador;
import br.edu.puccampinas.consultafacil.security.Autorizacao;
import br.edu.puccampinas.consultafacil.security.Sessoes;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import br.edu.puccampinas.consultafacil.service.AcessoConsulta;
import br.edu.puccampinas.consultafacil.service.HistoricoProcessor;
import br.edu.puccampinas.consultafacil.service.RegistroConsultaService;
import br.edu.puccampinas.consultafacil.service.StatusConsultaService;
import br.edu.puccampinas.consultafacil.support.ConsultasEmMemoria;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** HTTP, sessão e serviços reais da #28, com persistência isolada em memória (sem MongoDB). */
class StatusConsultaHttpTest {
    private static final Instant FIM = Instant.parse("2026-11-18T12:30:00Z");
    private static final String ORIGEM = "http://localhost:5173";
    private static final ObjectId PACIENTE = new ObjectId(), MEDICO = new ObjectId();
    private static final UsuarioLogado PACIENTE_LOGADO = usuario(Perfil.PACIENTE);
    private static final UsuarioLogado MEDICO_LOGADO = usuario(Perfil.MEDICO);
    private static final UsuarioLogado SECRETARIA = usuario(Perfil.SECRETARIA_ADMIN);
    private final ConsultasEmMemoria consultas = new ConsultasEmMemoria() {
        @Override public boolean atualizarComEvento(ObjectId id, int versao, Document campos, Document evento) {
            return !conflito && super.atualizarComEvento(id, versao, campos, evento);
        }
    };
    private final List<Document> eventos = new ArrayList<>();
    private final Sessoes sessoes = new Sessoes(Duration.ofMinutes(60));
    private final ObjectMapper json = new ObjectMapper();
    private boolean conflito, historicoIndisponivel;
    private HttpServer servidor;
    private HttpClient cliente;
    private String base;
    private ObjectId id;

    @BeforeEach void iniciar() throws Exception {
        id = new ObjectId();
        consultas.salvar(new Document("_id", id).append("pacienteId", PACIENTE).append("medicoId", MEDICO)
                .append("especialidadeId", new ObjectId()).append("inicio", Date.from(FIM.minusSeconds(1800)))
                .append("fim", Date.from(FIM)).append("status", "AGENDADA")
                .append("criadaEm", Date.from(FIM.minusSeconds(86400))).append("versao", 0));
        var relogio = Clock.fixed(FIM, ZoneOffset.UTC);
        var acesso = new AcessoConsulta(
                login -> Optional.ofNullable(PACIENTE_LOGADO.id().equals(login) ? PACIENTE : null),
                login -> Optional.ofNullable(MEDICO_LOGADO.id().equals(login) ? MEDICO : null));
        var registro = new RegistroConsultaService(consultas, new HistoricoProcessor(consultas, evento -> {
            if (historicoIndisponivel) throw new IllegalStateException("Falha de projeção simulada");
            eventos.add(evento);
        }), relogio);
        var handler = new StatusConsultaHandler(new StatusConsultaService(consultas, acesso, registro, relogio));
        var cors = new Cors(List.of(ORIGEM));
        var autz = new Autorizacao(new Autenticador(sessoes));
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/consultas/", Rotas.consultaPorId(cors, autz,
                ex -> Http.sendJson(ex, 200, Map.of("rota", "leitura")),
                ex -> Http.sendJson(ex, 200, Map.of("rota", "cancelamento")), handler));
        servidor.start();
        base = "http://127.0.0.1:" + servidor.getAddress().getPort();
        cliente = HttpClient.newHttpClient();
    }

    @AfterEach void encerrar() {
        if (cliente != null) cliente.close();
        if (servidor != null) servidor.stop(0);
    }

    @ParameterizedTest
    @EnumSource(Perfil.class)
    void confirmaComAtorDaSessaoEDevolveSomenteDto(Perfil perfil) throws Exception {
        var ator = switch (perfil) {
            case PACIENTE -> PACIENTE_LOGADO;
            case MEDICO -> MEDICO_LOGADO;
            case SECRETARIA_ADMIN -> SECRETARIA;
        };
        var resposta = alterar("CONFIRMADA", ator);
        assertEquals(200, resposta.statusCode(), resposta.body());
        JsonNode corpo = json.readTree(resposta.body());
        assertEquals(7, corpo.size());
        assertEquals(id.toHexString(), corpo.get("id").asText());
        assertEquals(PACIENTE.toHexString(), corpo.get("pacienteId").asText());
        assertEquals(MEDICO.toHexString(), corpo.get("medicoId").asText());
        assertEquals("CONFIRMADA", corpo.get("status").asText());
        assertEquals(FIM.toString(), corpo.get("fim").asText());
        assertFalse(corpo.has("versao"));
        assertFalse(corpo.has("eventosPendentes"));
        assertEquals(1, eventos.size());
        assertEquals("CONFIRMACAO", eventos.getFirst().getString("evento"));
        assertEquals(new ObjectId(ator.id()), eventos.getFirst().get("ator", Document.class).getObjectId("id"));
        assertEquals(ORIGEM, resposta.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
    }

    @ParameterizedTest
    @CsvSource({"CONFIRMADA,CONCLUIDA", "AGENDADA,FALTA", "CONFIRMADA,FALTA"})
    void medicoConcluiOuRegistraFaltaAposFimPrevisto(String anterior, String destino) throws Exception {
        consultas.docs.get(id).put("status", anterior);
        var resposta = alterar(destino, MEDICO_LOGADO);
        assertEquals(200, resposta.statusCode(), resposta.body());
        assertEquals(destino, json.readTree(resposta.body()).get("status").asText());
        assertEquals(anterior, eventos.getFirst().getString("statusAnterior"));
        assertEquals(destino, eventos.getFirst().getString("statusNovo"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "\"CONFIRMADA\"", "{}", "{", "{status:'CONFIRMADA'}",
            "{\"status\":null}", "{\"status\":1}", "{\"status\":true}", "{\"status\":[]}", "{\"status\":{}}",
            "{\"status\":\"inexistente\"}", "{\"status\":\"confirmada\"}", "{\"status\":\"AGENDADA\"}", "{\"status\":\"CANCELADA\"}",
            "{\"status\":\"CONFIRMADA\",\"inicio\":\"2026-12-01T12:00:00Z\"}",
            "{\"status\":\"CONFIRMADA\",\"pacienteId\":\"outro\"}", "{\"status\":\"CONFIRMADA\",\"versao\":0}",
            "{\"status\":\"CONFIRMADA\",\"ator\":{}}", "{\"status\":\"CONFIRMADA\",\"eventosPendentes\":[]}",
            "{\"status\":\"CONFIRMADA\",\"motivoCancelamento\":\"teste\"}",
            "{\"status\":\"CONFIRMADA\",\"status\":\"FALTA\"}", "{\"status\":\"CONFIRMADA\"} {}"})
    void rejeitaCorpoInvalidoSemEscrever(String corpo) throws Exception {
        var resposta = enviar("POST", caminho(), corpo, token(SECRETARIA));
        conferirErro(resposta, 400);
        assertEquals("AGENDADA", consultas.docs.get(id).getString("status"));
        assertEquals(0, consultas.docs.get(id).getInteger("versao"));
        assertTrue(eventos.isEmpty());
    }

    @Test void limitaTamanhoDoCorpo() throws Exception {
        conferirErro(enviar("POST", caminho(), "{\"status\":\"" + "X".repeat(16384) + "\"}", token(SECRETARIA)), 400);
        assertTrue(eventos.isEmpty());
    }

    @Test void exigeSessaoValidaInclusiveAposLogout() throws Exception {
        conferirErro(enviar("POST", caminho(), "{\"status\":\"CONFIRMADA\"}", null), 401);
        conferirErro(enviar("POST", caminho(), "{\"status\":\"CONFIRMADA\"}", "inventado"), 401);
        String token = token(SECRETARIA);
        sessoes.encerrar(token);
        conferirErro(enviar("POST", caminho(), "{\"status\":\"CONFIRMADA\"}", token), 401);
        assertTrue(eventos.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = Perfil.class, names = {"PACIENTE", "MEDICO"})
    void naoAlteraConsultaDeOutroCadastroNemLoginSemVinculo(Perfil perfil) throws Exception {
        var ator = perfil == Perfil.PACIENTE ? PACIENTE_LOGADO : MEDICO_LOGADO;
        conferirErro(alterar("CONFIRMADA", usuario(perfil)), 403);
        consultas.docs.get(id).put(perfil == Perfil.PACIENTE ? "pacienteId" : "medicoId", new ObjectId());
        conferirErro(alterar("CONFIRMADA", ator), 403);
        assertTrue(eventos.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONCLUIDA", "FALTA"})
    void pacienteNaoRegistraDesfecho(String destino) throws Exception {
        consultas.docs.get(id).put("status", "CONFIRMADA");
        conferirErro(alterar(destino, PACIENTE_LOGADO), 403);
        assertTrue(eventos.isEmpty());
    }

    @Test void distingueIdInvalidoDeConsultaInexistente() throws Exception {
        conferirErro(enviar("POST", "/consultas/invalido/status", "{\"status\":\"CONFIRMADA\"}", token(SECRETARIA)), 400);
        conferirErro(enviar("POST", "/consultas/" + new ObjectId() + "/status", "{\"status\":\"CONFIRMADA\"}", token(SECRETARIA)), 404);
    }

    @Test void transicaoPrazoEConcorrenciaRespondem409SemEvento() throws Exception {
        conferirErro(alterar("CONCLUIDA", SECRETARIA), 409);
        consultas.docs.get(id).put("status", "CANCELADA");
        conferirErro(alterar("CONFIRMADA", SECRETARIA), 409);
        consultas.docs.get(id).put("status", "CONFIRMADA");
        consultas.docs.get(id).put("fim", Date.from(FIM.plusMillis(1)));
        conferirErro(alterar("CONCLUIDA", SECRETARIA), 409);
        conferirErro(alterar("FALTA", SECRETARIA), 409);
        consultas.docs.get(id).put("status", "AGENDADA");
        conflito = true;
        conferirErro(alterar("CONFIRMADA", SECRETARIA), 409);
        assertEquals("AGENDADA", consultas.docs.get(id).getString("status"));
        assertTrue(eventos.isEmpty());
    }

    @Test void falhaDeProjecaoDevolveSucessoEPreservaEventoParaRecuperacao() throws Exception {
        historicoIndisponivel = true;
        assertEquals(200, alterar("CONFIRMADA", SECRETARIA).statusCode());
        assertEquals("CONFIRMADA", consultas.docs.get(id).getString("status"));
        assertEquals(1, consultas.docs.get(id).getList("eventosPendentes", Document.class).size());
        conferirErro(alterar("CONFIRMADA", SECRETARIA), 409);
        assertEquals(1, consultas.docs.get(id).getList("eventosPendentes", Document.class).size());
    }

    @Test void limiteDePendenciasResponde503SemAlterarStatus() throws Exception {
        historicoIndisponivel = true;
        List<Document> pendentes = new ArrayList<>();
        for (int i = 1; i <= ConsultaRepository.LIMITE_PENDENCIAS; i++)
            pendentes.add(new Document("_id", new ObjectId()).append("sequencia", i));
        consultas.docs.get(id).put("eventosPendentes", pendentes);
        conferirErro(alterar("CONFIRMADA", SECRETARIA), 503);
        assertEquals("AGENDADA", consultas.docs.get(id).getString("status"));
        assertTrue(eventos.isEmpty());
    }

    @Test void preservaLeituraCancelamentoEPreflight() throws Exception {
        String token = token(SECRETARIA);
        for (String rota : List.of("/consultas/lista", "/consultas/" + id))
            assertEquals("leitura", json.readTree(enviar("GET", rota, "", token).body()).get("rota").asText());
        assertEquals("cancelamento", json.readTree(enviar("POST", "/consultas/" + id + "/cancelamento", "", token).body()).get("rota").asText());
        for (String rota : List.of(caminho() + "/extra", caminho() + "/", "/consultas/" + id + "/outra"))
            conferirErro(enviar("POST", rota, "{}", token), 404);
        conferirErro(enviar("GET", caminho(), "", token), 404);
        var preflight = enviar("OPTIONS", caminho(), "", null);
        assertEquals(204, preflight.statusCode());
        assertEquals(ORIGEM, preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertTrue(eventos.isEmpty());
    }

    private static UsuarioLogado usuario(Perfil perfil) { return new UsuarioLogado(new ObjectId().toHexString(), "Teste", perfil); }
    private String token(UsuarioLogado ator) { return sessoes.criar(ator).token(); }
    private String caminho() { return "/consultas/" + id.toHexString() + "/status"; }
    private HttpResponse<String> alterar(String destino, UsuarioLogado ator) throws Exception {
        return enviar("POST", caminho(), "{\"status\":\"" + destino + "\"}", token(ator));
    }
    private HttpResponse<String> enviar(String metodo, String rota, String corpo, String token) throws Exception {
        var pedido = HttpRequest.newBuilder(URI.create(base + rota)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Origin", ORIGEM)
                .method(metodo, HttpRequest.BodyPublishers.ofString(corpo));
        if (token != null) pedido.header("Authorization", "Bearer " + token);
        return cliente.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
    }
    private void conferirErro(HttpResponse<String> resposta, int esperado) throws Exception {
        assertEquals(esperado, resposta.statusCode(), resposta.body());
        JsonNode corpo = json.readTree(resposta.body());
        assertEquals(esperado, corpo.get("status").asInt());
        assertFalse(corpo.get("erro").asText().isBlank());
    }
}
