/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.*;
import br.edu.puccampinas.consultafacil.api.dto.ConsultaCalendarioResponse;
import br.edu.puccampinas.consultafacil.api.handler.ConsultaCalendarioHandler;
import br.edu.puccampinas.consultafacil.api.middleware.Cors;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.repository.*;
import br.edu.puccampinas.consultafacil.security.*;
import br.edu.puccampinas.consultafacil.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoDatabase;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

// Os cenários com fusos distintos conferem o dia da clínica solicitado pelo painel e
// preservam o comportamento das chamadas sem filtro de fuso.
class CalendarioFusoHttpTest {
    private static final ObjectId MEDICO = new ObjectId(), OUTRO = new ObjectId(), PACIENTE = new ObjectId(), ESPECIALIDADE = new ObjectId();
    private static final UsuarioLogado SECRETARIA = ator(Perfil.SECRETARIA_ADMIN), MEDICO_LOGADO = ator(Perfil.MEDICO), PACIENTE_LOGADO = ator(Perfil.PACIENTE);
    private final Sessoes sessoes = new Sessoes(Duration.ofMinutes(60));
    private final List<Document> consultas = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();
    private Document agenda = new Document("fusoHorario", "UTC");
    private Date inicioLido, fimLido;
    private ObjectId medicoLido;
    private int leituras;
    private ConsultaCalendarioService service;
    private HttpServer servidor;
    private HttpClient cliente;
    private ExecutorService executor;
    private String base;

    private static UsuarioLogado ator(Perfil perfil) { return new UsuarioLogado(new ObjectId().toHexString(), "Teste", perfil); }

    @BeforeEach void iniciar() throws Exception {
        MongoDatabase db = (MongoDatabase) Proxy.newProxyInstance(MongoDatabase.class.getClassLoader(), new Class<?>[]{MongoDatabase.class},
                (p, m, a) -> { if (m.getName().equals("getCollection")) return null; throw new AssertionError(m.getName()); });
        var repo = new ConsultaRepository(db) {
            @Override public List<Document> doMedicoNoPeriodo(ObjectId medico, Date inicio, Date fim) {
                leituras++; medicoLido = medico; inicioLido = inicio; fimLido = fim;
                return consultas.stream().filter(d -> medico.equals(d.getObjectId("medicoId"))
                        && d.getDate("inicio").before(fim) && d.getDate("fim").after(inicio))
                        .sorted(Comparator.comparing(d -> d.getDate("inicio"))).toList();
            }
        };
        var agendas = new AgendaRepository(db) {
            @Override public Optional<Document> buscarPorMedico(ObjectId id) { return Optional.ofNullable(agenda); }
        };
        var medicos = new MedicoRepository(db) {
            @Override public boolean existeAtivo(ObjectId id) { return MEDICO.equals(id) || OUTRO.equals(id); }
        };
        var nomes = new NomesRepository() {
            @Override public Map<ObjectId, String> pacientes(Collection<ObjectId> ids) { return Map.of(PACIENTE, "Paciente Teste"); }
            @Override public Map<ObjectId, String> especialidades(Collection<ObjectId> ids) { return Map.of(ESPECIALIDADE, "Clínica Geral"); }
        };
        service = new ConsultaCalendarioService(repo, agendas, medicos, nomes);
        var acesso = new AcessoAgenda(new MedicoDoUsuarioRepository(db) {
            @Override public Optional<ObjectId> buscar(String id) { return MEDICO_LOGADO.id().equals(id) ? Optional.of(MEDICO) : Optional.empty(); }
        });
        HttpHandler eco = ex -> Http.sendJson(ex, 200, Map.of("rota", "existente"));
        var handlers = new Rotas.Handlers(eco, eco, eco, eco, eco, eco, eco, new ConsultaCalendarioHandler(service, acesso), eco, eco, eco);
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        servidor.setExecutor(executor);
        servidor.createContext("/", Rotas.router(new Cors(List.of("http://localhost:5173")), new Autorizacao(new Autenticador(sessoes)), handlers));
        servidor.start();
        base = "http://127.0.0.1:" + servidor.getAddress().getPort();
        cliente = HttpClient.newHttpClient();
        inserir("2026-11-23T00:30:00Z", 1800);
        inserir("2026-11-23T02:30:00Z", 3600);
        inserir("2026-11-23T03:00:00Z", 1800);
        inserir("2026-11-24T02:30:00Z", 1800);
        inserir("2026-11-24T03:00:00Z", 1800);
    }

    @AfterEach void encerrar() {
        if (cliente != null) cliente.close();
        if (servidor != null) servidor.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    private void inserir(String inicio, long duracao) {
        consultas.add(new Document("_id", new ObjectId()).append("medicoId", MEDICO).append("pacienteId", PACIENTE)
                .append("especialidadeId", ESPECIALIDADE).append("status", "AGENDADA")
                .append("inicio", Date.from(Instant.parse(inicio))).append("fim", Date.from(Instant.parse(inicio).plusSeconds(duracao))));
    }

    private HttpResponse<String> get(String filtro, String adicional, UsuarioLogado ator) throws Exception {
        var pedido = HttpRequest.newBuilder(URI.create(base + "/consultas?medicoId=" + filtro + "&de=2026-11-23&ate=2026-11-23" + adicional));
        if (ator != null) pedido.header("Authorization", "Bearer " + sessoes.criar(ator).token());
        return cliente.send(pedido.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void chamadaAtualMantemFusoDaAgendaEJanelaOriginal() throws Exception {
        var resposta = get(MEDICO.toHexString(), "", SECRETARIA);
        assertEquals(200, resposta.statusCode(), resposta.body());
        assertEquals(Instant.parse("2026-11-23T00:00:00Z"), inicioLido.toInstant());
        assertEquals(Instant.parse("2026-11-24T00:00:00Z"), fimLido.toInstant());
        var corpo = json.readTree(resposta.body());
        assertEquals(3, corpo.size());
        assertEquals("2026-11-23T00:30", corpo.get(0).get("inicio").asText());
    }

    @Test void painelUsaDiaDaClinicaParaBuscarEExibirSemAlterarConsultas() throws Exception {
        String antes = new Document("consultas", consultas).toJson();
        var resposta = get(MEDICO.toHexString(), "&fusoHorario=America%2FSao_Paulo", SECRETARIA);
        assertEquals(200, resposta.statusCode(), resposta.body());
        assertEquals(Instant.parse("2026-11-23T03:00:00Z"), inicioLido.toInstant());
        assertEquals(Instant.parse("2026-11-24T03:00:00Z"), fimLido.toInstant());
        var corpo = json.readTree(resposta.body());
        assertEquals(3, corpo.size());
        assertEquals("2026-11-22T23:30", corpo.get(0).get("inicio").asText());
        assertEquals("2026-11-23T00:00", corpo.get(1).get("inicio").asText());
        assertEquals("2026-11-23T23:30", corpo.get(2).get("inicio").asText());
        assertEquals(antes, new Document("consultas", consultas).toJson());
        for (String campo : List.of("eventosPendentes", "versao", "$oid")) assertFalse(resposta.body().contains(campo));
    }

    @Test void sobrecargaAntigaContinuaUsandoOMesmoContrato() {
        var resultado = service.listar(MEDICO, "2026-11-23", "2026-11-23");
        assertEquals(List.of("2026-11-23T00:30", "2026-11-23T02:30", "2026-11-23T03:00"),
                resultado.stream().map(ConsultaCalendarioResponse::inicio).toList());
    }

    @Test void diaHistoricoComHorarioDeVeraoUsaInicioRealDoDia() {
        service.listar(MEDICO, "2018-11-04", "2018-11-04", AgendaService.FUSO_PADRAO);
        assertEquals(Instant.parse("2018-11-04T03:00:00Z"), inicioLido.toInstant());
        assertEquals(Instant.parse("2018-11-05T02:00:00Z"), fimLido.toInstant());
    }

    @Test void agendaAusentePreservaPadraoDaClinicaNasDuasChamadas() {
        agenda = null;
        assertEquals(service.listar(MEDICO, "2026-11-23", "2026-11-23"),
                service.listar(MEDICO, "2026-11-23", "2026-11-23", AgendaService.FUSO_PADRAO));
    }

    @ParameterizedTest @ValueSource(strings = {"", "UTC", "Invalido%2FLocal"})
    void fusoNaoSuportadoRetorna400SemLerConsultas(String fuso) throws Exception {
        assertEquals(400, get(MEDICO.toHexString(), "&fusoHorario=" + fuso, SECRETARIA).statusCode());
        assertEquals(0, leituras);
    }

    @Test void medicoMeusDadosResolvidoPorCadastroComFusoExplicito() throws Exception {
        assertNotEquals(MEDICO_LOGADO.id(), MEDICO.toHexString());
        assertEquals(200, get("meu", "&fusoHorario=America%2FSao_Paulo", MEDICO_LOGADO).statusCode());
        assertEquals(MEDICO, medicoLido);
    }

    @Test void medicoNaoPodeTrocarEscopoPeloFiltroOuPeloFuso() throws Exception {
        assertEquals(403, get(OUTRO.toHexString(), "&fusoHorario=America%2FSao_Paulo", MEDICO_LOGADO).statusCode());
        assertEquals(0, leituras);
    }

    @Test void medicoSemCadastroContinuaRecebendo403() throws Exception {
        assertEquals(403, get("meu", "&fusoHorario=America%2FSao_Paulo", ator(Perfil.MEDICO)).statusCode());
        assertEquals(0, leituras);
    }

    @Test void pacienteESessaoAusenteContinuamBloqueados() throws Exception {
        assertEquals(403, get(MEDICO.toHexString(), "&fusoHorario=America%2FSao_Paulo", PACIENTE_LOGADO).statusCode());
        assertEquals(401, get(MEDICO.toHexString(), "&fusoHorario=America%2FSao_Paulo", null).statusCode());
        assertEquals(0, leituras);
    }
}
