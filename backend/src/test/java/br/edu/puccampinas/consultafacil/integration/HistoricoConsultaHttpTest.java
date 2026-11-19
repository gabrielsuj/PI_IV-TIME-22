package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.Rotas;
import br.edu.puccampinas.consultafacil.api.handler.HistoricoConsultaHandler;
import br.edu.puccampinas.consultafacil.api.middleware.Cors;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.repository.NomesRepository;
import br.edu.puccampinas.consultafacil.security.*;
import br.edu.puccampinas.consultafacil.service.*;
import br.edu.puccampinas.consultafacil.support.ConsultasEmMemoria;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** HTTP e autorização reais; eventos gerados pelo registro auditado, sem depender de MongoDB. */
class HistoricoConsultaHttpTest {
    private static final ObjectId PACIENTE = new ObjectId(), MEDICO = new ObjectId();
    private static final UsuarioLogado PACIENTE_LOGADO = usuario(Perfil.PACIENTE);
    private static final UsuarioLogado MEDICO_LOGADO = usuario(Perfil.MEDICO);
    private static final UsuarioLogado SECRETARIA = usuario(Perfil.SECRETARIA_ADMIN);
    private final ConsultasEmMemoria consultas = new ConsultasEmMemoria();
    private final List<Document> eventos = new ArrayList<>();
    private final Sessoes sessoes = new Sessoes(Duration.ofMinutes(60));
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer servidor;
    private HttpClient cliente;
    private String base;
    private ObjectId id;
    private int leituras;
    private RegistroConsultaService registro;
    private final Map<ObjectId, Document> usuarios = new HashMap<>();
    private final List<Set<ObjectId>> lotesNomes = new ArrayList<>();

    @BeforeEach void iniciar() throws Exception {
        registro = new RegistroConsultaService(consultas, new HistoricoProcessor(consultas, eventos::add),
                Clock.fixed(Instant.parse("2026-11-18T12:30:00Z"), ZoneOffset.UTC));
        id = registro.criar(new Document("pacienteId", PACIENTE).append("medicoId", MEDICO)
                .append("especialidadeId", new ObjectId()).append("inicio", Date.from(Instant.parse("2026-12-01T12:00:00Z")))
                .append("fim", Date.from(Instant.parse("2026-12-01T12:30:00Z"))), SECRETARIA).getObjectId("_id");
        var acesso = new AcessoConsulta(
                login -> Optional.ofNullable(PACIENTE_LOGADO.id().equals(login) ? PACIENTE : null),
                login -> Optional.ofNullable(MEDICO_LOGADO.id().equals(login) ? MEDICO : null));
        usuarios.put(new ObjectId(SECRETARIA.id()), new Document("nome", "Sofia Secretaria")
                .append("email", "privado@example.com").append("senha", new Document("hash", "credencial-interna")));
        NomesRepository nomes = new NomesRepository() {
            public Map<ObjectId, String> pacientes(Collection<ObjectId> ids) { throw new AssertionError("Usar usuarios._id"); }
            public Map<ObjectId, String> especialidades(Collection<ObjectId> ids) { throw new AssertionError("Usar usuarios._id"); }
            @Override public Map<ObjectId, String> usuarios(Collection<ObjectId> ids) {
                lotesNomes.add(Set.copyOf(ids));
                Map<ObjectId, String> resultado = new HashMap<>();
                for (ObjectId usuarioId : ids) if (usuarios.containsKey(usuarioId))
                    resultado.put(usuarioId, usuarios.get(usuarioId).getString("nome"));
                return resultado;
            }
        };
        var handler = new HistoricoConsultaHandler(new HistoricoConsultaService(consultas, (consultaId, apos, limite) -> {
            leituras++;
            return eventos.stream().filter(e -> consultaId.equals(e.getObjectId("consultaId")))
                    .filter(e -> e.getInteger("sequencia") > apos)
                    .sorted(Comparator.comparingInt(e -> e.getInteger("sequencia"))).limit(limite).toList();
        }, acesso, nomes));
        var cors = new Cors(List.of("http://localhost:5173"));
        var autz = new Autorizacao(new Autenticador(sessoes));
        HttpHandler eco = ex -> Http.sendJson(ex, 200, Map.of("rota", "existente"));
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/consultas/", Rotas.consultaPorId(cors, autz, eco, eco, eco, handler));
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
    void devolveDtosParaCadaPerfilAutorizado(Perfil perfil) throws Exception {
        var ator = switch (perfil) { case PACIENTE -> PACIENTE_LOGADO; case MEDICO -> MEDICO_LOGADO; case SECRETARIA_ADMIN -> SECRETARIA; };
        var resposta = enviar("GET", caminho(), token(ator));
        assertEquals(200, resposta.statusCode(), resposta.body());
        var corpo = json.readTree(resposta.body());
        assertEquals(id.toHexString(), corpo.get("consultaId").asText());
        assertEquals(100, corpo.get("limite").asInt());
        assertEquals(0, corpo.get("aposSequencia").asInt());
        assertFalse(corpo.get("limiteAtingido").asBoolean());
        assertFalse(corpo.get("temMais").asBoolean());
        assertFalse(corpo.get("atualizacaoPendente").asBoolean());
        assertFalse(corpo.has("proximaSequencia"));
        assertEquals(1, corpo.get("eventos").size());
        var evento = corpo.get("eventos").get(0);
        assertTrue(evento.get("id").isTextual());
        assertEquals("CRIACAO", evento.get("evento").asText());
        assertEquals("AGENDADA", evento.get("statusNovo").asText());
        assertEquals("2026-11-18T12:30:00Z", evento.get("ocorridoEm").asText());
        assertEquals(SECRETARIA.id(), evento.get("ator").get("id").asText());
        assertEquals("Sofia Secretaria", evento.get("ator").get("nome").asText());
        assertEquals(List.of(Set.of(new ObjectId(SECRETARIA.id()))), lotesNomes);
        assertFalse(resposta.body().contains("privado@example.com"));
        assertFalse(resposta.body().contains("credencial-interna"));
        assertFalse(resposta.body().contains("senha"));
        assertFalse(resposta.body().contains("eventosPendentes"));
        assertFalse(resposta.body().contains("versao"));
        assertFalse(resposta.body().contains("$oid"));
        assertEquals(1, leituras);
    }

    @ParameterizedTest
    @EnumSource(value = Perfil.class, names = {"PACIENTE", "MEDICO"})
    void recusaOutroCadastroELoginSemVinculoAntesDeLerEventos(Perfil perfil) throws Exception {
        var ator = perfil == Perfil.PACIENTE ? PACIENTE_LOGADO : MEDICO_LOGADO;
        conferirErro(enviar("GET", caminho(), token(usuario(perfil))), 403);
        consultas.docs.get(id).put(perfil == Perfil.PACIENTE ? "pacienteId" : "medicoId", new ObjectId());
        conferirErro(enviar("GET", caminho(), token(ator)), 403);
        conferirErro(enviar("GET", caminho() + "?aposSequencia=1&limite=1", token(ator)), 403);
        assertEquals(0, leituras);
        assertTrue(lotesNomes.isEmpty());
    }

    @Test void acaoSalvaComHistoricoPendenteSinalizaAtrasoSemReprocessarNoGet() throws Exception {
        var registroComFalha = new RegistroConsultaService(consultas, new HistoricoProcessor(consultas,
                evento -> { throw new IllegalStateException("Projeção indisponível"); }), Clock.systemUTC());
        var salva = registroComFalha.alterar(id, new Document("status", "CONFIRMADA"), SECRETARIA);
        assertEquals("CONFIRMADA", salva.getString("status"));
        var antes = consultas.buscar(id).orElseThrow();
        String token = token(SECRETARIA);
        var resposta = enviar("GET", caminho(), token);
        assertEquals(200, resposta.statusCode(), resposta.body());
        var corpo = json.readTree(resposta.body());
        assertTrue(corpo.get("atualizacaoPendente").isBoolean());
        assertTrue(corpo.get("atualizacaoPendente").asBoolean());
        assertEquals(1, corpo.get("eventos").size());
        assertEquals("CRIACAO", corpo.get("eventos").get(0).get("evento").asText());
        assertFalse(corpo.get("temMais").asBoolean());
        assertFalse(corpo.has("proximaSequencia"));
        assertFalse(resposta.body().contains("eventosPendentes"));
        assertFalse(resposta.body().contains("versao"));
        assertEquals(antes, consultas.buscar(id).orElseThrow());
        assertEquals(1, eventos.size());
        new HistoricoProcessor(consultas, eventos::add).processarConsulta(id);
        var atualizada = json.readTree(enviar("GET", caminho(), token).body());
        assertFalse(atualizada.get("atualizacaoPendente").asBoolean());
        assertEquals(2, atualizada.get("eventos").size());
        assertEquals("CONFIRMACAO", atualizada.get("eventos").get(1).get("evento").asText());
        assertEquals(antes.getInteger("versao"), consultas.buscar(id).orElseThrow().getInteger("versao"));
    }

    @Test void exigeSessaoValida() throws Exception {
        conferirErro(enviar("GET", caminho(), null), 401);
        conferirErro(enviar("GET", caminho(), "token-inventado"), 401);
        String token = token(SECRETARIA);
        sessoes.encerrar(token);
        conferirErro(enviar("GET", caminho(), token), 401);
        assertEquals(0, leituras);
    }

    @Test void distingueIdInvalidoConsultaInexistenteEListaVazia() throws Exception {
        String token = token(SECRETARIA);
        conferirErro(enviar("GET", "/consultas/invalido/historico", token), 400);
        conferirErro(enviar("GET", "/consultas/" + new ObjectId() + "/historico", token), 404);
        assertEquals(0, leituras);
        eventos.clear();
        var resposta = enviar("GET", caminho(), token);
        assertEquals(200, resposta.statusCode());
        assertTrue(json.readTree(resposta.body()).get("eventos").isEmpty());
    }

    @Test void preservaRotasExistentesEMetodosDoHistorico() throws Exception {
        String token = token(SECRETARIA);
        for (String rota : List.of("/consultas/lista", "/consultas/" + id))
            assertEquals("existente", json.readTree(enviar("GET", rota, token).body()).get("rota").asText());
        for (String acao : List.of("status", "cancelamento"))
            assertEquals(200, enviar("POST", "/consultas/" + id + "/" + acao, token).statusCode());
        for (String metodo : List.of("POST", "PUT", "DELETE")) conferirErro(enviar(metodo, caminho(), token), 404);
        for (String sufixo : List.of("/", "/extra")) conferirErro(enviar("GET", caminho() + sufixo, token), 404);
        conferirErro(enviar("GET", caminho() + "?pacienteId=outro", token), 400);
        var preflight = enviar("OPTIONS", caminho(), null);
        assertEquals(204, preflight.statusCode());
        assertEquals("http://localhost:5173", preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals(0, leituras);
    }

    @Test void paginaPeloCursorSemRepetirEInformaFimMesmoComPaginaCheia() throws Exception {
        acrescentarEventos(3);
        String token = token(SECRETARIA);
        var primeira = json.readTree(enviar("GET", caminho() + "?limite=2", token).body());
        assertEquals(1, primeira.get("eventos").get(0).get("sequencia").asInt());
        assertEquals(2, primeira.get("eventos").get(1).get("sequencia").asInt());
        assertEquals(2, primeira.get("limite").asInt());
        assertTrue(primeira.get("temMais").asBoolean());
        int cursor = primeira.get("proximaSequencia").asInt();
        assertEquals(2, cursor);
        var segunda = json.readTree(enviar("GET", caminho() + "?aposSequencia=" + cursor + "&limite=2", token).body());
        assertEquals(2, segunda.get("aposSequencia").asInt());
        assertEquals(3, segunda.get("eventos").get(0).get("sequencia").asInt());
        assertEquals(4, segunda.get("eventos").get(1).get("sequencia").asInt());
        assertTrue(segunda.get("limiteAtingido").asBoolean());
        assertFalse(segunda.get("temMais").asBoolean());
        assertFalse(segunda.has("proximaSequencia"));
        var vazia = json.readTree(enviar("GET", caminho() + "?aposSequencia=4&limite=2", token).body());
        assertTrue(vazia.get("eventos").isEmpty());
        assertFalse(vazia.get("temMais").asBoolean());
        assertFalse(vazia.has("proximaSequencia"));
    }

    @Test void paginaPadraoDeCemDistingueTetoDeMaisEventos() throws Exception {
        acrescentarEventos(99);
        String token = token(SECRETARIA);
        var ultimaCheia = json.readTree(enviar("GET", caminho(), token).body());
        assertEquals(100, ultimaCheia.get("eventos").size());
        assertTrue(ultimaCheia.get("limiteAtingido").asBoolean());
        assertFalse(ultimaCheia.get("temMais").asBoolean());
        acrescentarEventos(1);
        var primeira = json.readTree(enviar("GET", caminho(), token).body());
        assertEquals(100, primeira.get("eventos").size());
        assertTrue(primeira.get("temMais").asBoolean());
        assertEquals(100, primeira.get("proximaSequencia").asInt());
        var ultima = json.readTree(enviar("GET", caminho() + "?aposSequencia=100", token).body());
        assertEquals(1, ultima.get("eventos").size());
        assertEquals(101, ultima.get("eventos").get(0).get("sequencia").asInt());
        assertFalse(ultima.get("temMais").asBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"limite=0", "limite=-1", "limite=101", "limite=2147483648", "limite=1.5", "limite=true",
            "limite=", "limite", "limite=%2B1", "limite=%201", "limite=1%20", "limite=%EF%BC%91",
            "aposSequencia=-1", "aposSequencia=2147483648", "aposSequencia=1.5", "aposSequencia=", "aposSequencia=null",
            "aposSequencia=%2B1", "pagina=1", "pacienteId=outro", "limite=2&limite=3", "aposSequencia=0&aposSequencia=1",
            "%6Cimite=1&limite=2", "limite=1&", "&limite=1", "limite=1&&aposSequencia=0"})
    void recusaParametrosInvalidosRepetidosOuDesconhecidos(String query) throws Exception {
        conferirErro(enviar("GET", caminho() + "?" + query, token(SECRETARIA)), 400);
        assertEquals(0, leituras);
    }

    @Test void aceitaLimitesDoCursorENormalizaInteirosValidos() throws Exception {
        String token = token(SECRETARIA);
        var primeira = json.readTree(enviar("GET", caminho() + "?limite=01&aposSequencia=0000", token).body());
        assertEquals(1, primeira.get("limite").asInt());
        assertEquals(0, primeira.get("aposSequencia").asInt());
        assertFalse(primeira.get("temMais").asBoolean());
        var resposta = enviar("GET", caminho() + "?%6Cimite=1&aposSequencia=2147483647", token);
        assertEquals(200, resposta.statusCode(), resposta.body());
        var ultima = json.readTree(resposta.body());
        assertEquals(Integer.MAX_VALUE, ultima.get("aposSequencia").asInt());
        assertTrue(ultima.get("eventos").isEmpty());
    }

    private void acrescentarEventos(int quantidade) {
        for (int i = 0; i < quantidade; i++) registro.alterar(id, new Document("medicoId", new ObjectId()), SECRETARIA);
    }

    @Test void alteracaoExibeReferenciasDatasENulosSemBsonBruto() throws Exception {
        ObjectId especialidade = new ObjectId(), medicoNovo = new ObjectId();
        consultas.docs.get(id).remove("especialidadeId"); // Registro legado completado pela alteração auditada.
        registro.alterar(id, new Document("especialidadeId", especialidade).append("medicoId", medicoNovo)
                .append("inicio", Date.from(Instant.parse("2026-12-02T13:00:00Z")))
                .append("fim", Date.from(Instant.parse("2026-12-02T13:30:00Z"))), SECRETARIA);
        var resposta = enviar("GET", caminho() + "?aposSequencia=1", token(SECRETARIA));
        assertEquals(200, resposta.statusCode(), resposta.body());
        var corpo = json.readTree(resposta.body());
        var evento = corpo.get("eventos").get(0);
        assertEquals("ALTERACAO", evento.get("evento").asText());
        assertEquals(4, evento.get("alteracoes").size());
        for (var mudanca : evento.get("alteracoes")) {
            switch (mudanca.get("campo").asText()) {
                case "especialidadeId" -> {
                    assertTrue(mudanca.get("valorAnterior").isNull());
                    assertEquals(especialidade.toHexString(), mudanca.get("valorNovo").asText());
                }
                case "medicoId" -> assertEquals(medicoNovo.toHexString(), mudanca.get("valorNovo").asText());
                case "inicio" -> assertEquals("2026-12-02T13:00:00Z", mudanca.get("valorNovo").asText());
                case "fim" -> assertEquals("2026-12-02T13:30:00Z", mudanca.get("valorNovo").asText());
                default -> fail("Campo inesperado no DTO");
            }
        }
        assertFalse(resposta.body().contains("$oid"));
        assertFalse(resposta.body().contains("$date"));
        assertFalse(corpo.has("proximaSequencia"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cancelamentoExibeMotivoSomenteQuandoRegistrado(boolean comMotivo) throws Exception {
        Document campos = new Document("status", "CANCELADA");
        if (comMotivo) campos.append("motivoCancelamento", "Viagem");
        registro.alterar(id, campos, SECRETARIA);
        var resposta = enviar("GET", caminho() + "?aposSequencia=1", token(SECRETARIA));
        assertEquals(200, resposta.statusCode(), resposta.body());
        var evento = json.readTree(resposta.body()).get("eventos").get(0);
        assertEquals("CANCELAMENTO", evento.get("evento").asText());
        assertEquals(comMotivo, evento.has("motivo"));
        if (comMotivo) assertEquals("Viagem", evento.get("motivo").asText());
        assertFalse(evento.has("alteracoes"));
        assertFalse(evento.has("totalSinalizados"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5})
    void sinalizacaoExibeTotalEIdNuloDoSistema(int total) throws Exception {
        registro.alterar(id, new Document("status", "CANCELADA"), SECRETARIA);
        int versao = consultas.docs.get(id).getInteger("versao");
        Document cancelamento = eventos.getLast();
        // Payload produzido pela #32; a transferência usa a infraestrutura real do histórico.
        Document sinalizacao = new Document(cancelamento).append("_id", new ObjectId()).append("sequencia", versao + 1)
                .append("evento", "SINALIZACAO_FILA").append("statusAnterior", "CANCELADA")
                .append("ator", new Document("perfil", "SISTEMA").append("id", null)).append("totalSinalizados", total);
        assertTrue(consultas.atualizarComEvento(id, versao, new Document(), sinalizacao));
        new HistoricoProcessor(consultas, eventos::add).processarConsulta(id);
        var resposta = enviar("GET", caminho() + "?aposSequencia=2", token(SECRETARIA));
        assertEquals(200, resposta.statusCode(), resposta.body());
        var evento = json.readTree(resposta.body()).get("eventos").get(0);
        assertEquals("SINALIZACAO_FILA", evento.get("evento").asText());
        assertEquals(total, evento.get("totalSinalizados").asInt());
        assertEquals("SISTEMA", evento.get("ator").get("perfil").asText());
        assertTrue(evento.get("ator").get("id").isNull());
        assertEquals("Sistema", evento.get("ator").get("nome").asText());
        assertTrue(lotesNomes.isEmpty());
        assertFalse(evento.has("motivo"));
        assertFalse(evento.has("alteracoes"));
    }

    @Test void nomesDosResponsaveisVemDeUmUnicoLoteDeUsuarios() throws Exception {
        registro.alterar(id, new Document("status", "CONFIRMADA"), PACIENTE_LOGADO);
        registro.alterar(id, new Document("inicio", Date.from(Instant.parse("2026-12-01T12:05:00Z"))), SECRETARIA);
        registro.alterar(id, new Document("status", "CONCLUIDA"), MEDICO_LOGADO);
        usuarios.put(new ObjectId(PACIENTE_LOGADO.id()), new Document("nome", "Paulo Paciente").append("ativo", false));
        usuarios.put(new ObjectId(MEDICO_LOGADO.id()), new Document("nome", "Ana Médica"));
        var resposta = enviar("GET", caminho(), token(SECRETARIA));
        assertEquals(200, resposta.statusCode(), resposta.body());
        var lista = json.readTree(resposta.body()).get("eventos");
        assertEquals("Sofia Secretaria", lista.get(0).get("ator").get("nome").asText());
        assertEquals("Paulo Paciente", lista.get(1).get("ator").get("nome").asText());
        assertEquals("Sofia Secretaria", lista.get(2).get("ator").get("nome").asText());
        assertEquals("Ana Médica", lista.get(3).get("ator").get("nome").asText());
        assertEquals(List.of(Set.of(new ObjectId(SECRETARIA.id()), new ObjectId(PACIENTE_LOGADO.id()), new ObjectId(MEDICO_LOGADO.id()))), lotesNomes);
        assertEquals(PACIENTE_LOGADO.id(), lista.get(1).get("ator").get("id").asText());
        assertEquals("PACIENTE", lista.get(1).get("ator").get("perfil").asText());
        assertFalse(resposta.body().contains("credencial-interna"));
        assertFalse(resposta.body().contains("privado@example.com"));
    }

    @Test void usuarioRemovidoPreservaIdEPerfilSemImpedirLeituraDoHistorico() throws Exception {
        usuarios.clear();
        var resposta = enviar("GET", caminho(), token(SECRETARIA));
        assertEquals(200, resposta.statusCode(), resposta.body());
        var ator = json.readTree(resposta.body()).get("eventos").get(0).get("ator");
        assertEquals("Usuário não encontrado", ator.get("nome").asText());
        assertEquals(SECRETARIA.id(), ator.get("id").asText());
        assertEquals("SECRETARIA_ADMIN", ator.get("perfil").asText());
    }

    @Test void eventoLegadoSemAtorNaoConsultaUsuariosNemInventaIdentidade() throws Exception {
        eventos.getFirst().remove("ator");
        var resposta = enviar("GET", caminho(), token(SECRETARIA));
        assertEquals(200, resposta.statusCode(), resposta.body());
        var ator = json.readTree(resposta.body()).get("eventos").get(0).get("ator");
        assertEquals("Responsável não identificado", ator.get("nome").asText());
        assertTrue(ator.get("id").isNull());
        assertTrue(ator.get("perfil").isNull());
        assertTrue(lotesNomes.isEmpty());
    }

    private static UsuarioLogado usuario(Perfil perfil) { return new UsuarioLogado(new ObjectId().toHexString(), "Teste", perfil); }
    private String token(UsuarioLogado ator) { return sessoes.criar(ator).token(); }
    private String caminho() { return "/consultas/" + id + "/historico"; }
    private HttpResponse<String> enviar(String metodo, String rota, String token) throws Exception {
        var pedido = HttpRequest.newBuilder(URI.create(base + rota)).timeout(Duration.ofSeconds(10))
                .header("Origin", "http://localhost:5173").method(metodo, HttpRequest.BodyPublishers.noBody());
        if (token != null) pedido.header("Authorization", "Bearer " + token);
        return cliente.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
    }
    private void conferirErro(HttpResponse<String> resposta, int status) throws Exception {
        assertEquals(status, resposta.statusCode(), resposta.body());
        var corpo = json.readTree(resposta.body());
        assertEquals(status, corpo.get("status").asInt());
        assertFalse(corpo.get("erro").asText().isBlank());
    }
}
