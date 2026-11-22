/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387

Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.dto.ConsultaResponse;
import br.edu.puccampinas.consultafacil.api.dto.AgendamentoRequest;
import br.edu.puccampinas.consultafacil.api.Rotas;
import br.edu.puccampinas.consultafacil.api.handler.AvisoVagaHandler;
import br.edu.puccampinas.consultafacil.api.handler.FilaEsperaHandler;
import br.edu.puccampinas.consultafacil.api.middleware.Cors;
import br.edu.puccampinas.consultafacil.api.handler.AgendaHandler;
import br.edu.puccampinas.consultafacil.api.handler.BloqueioHandler;
import br.edu.puccampinas.consultafacil.service.AcessoAgenda;
import br.edu.puccampinas.consultafacil.service.AgendaService;
import br.edu.puccampinas.consultafacil.service.BloqueioService;
import br.edu.puccampinas.consultafacil.service.ConsultasAfetadas;
import br.edu.puccampinas.consultafacil.config.MongoConnection;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.repository.AgendaRepository;
import br.edu.puccampinas.consultafacil.repository.BloqueioRepository;
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
import br.edu.puccampinas.consultafacil.service.AgendamentoService;
import br.edu.puccampinas.consultafacil.service.AvisoVagaService;
import br.edu.puccampinas.consultafacil.service.DisponibilidadeService;
import br.edu.puccampinas.consultafacil.service.FilaEsperaService;
import br.edu.puccampinas.consultafacil.service.HistoricoProcessor;
import br.edu.puccampinas.consultafacil.service.RegistroConsultaService;
import br.edu.puccampinas.consultafacil.service.SinalizacaoFilaService;
import br.edu.puccampinas.consultafacil.support.RelogioAjustavel;
import br.edu.puccampinas.consultafacil.api.handler.CentralNotificacoesHandler;
import br.edu.puccampinas.consultafacil.service.AcessoConsulta;
import br.edu.puccampinas.consultafacil.service.CentralNotificacoesService;
import br.edu.puccampinas.consultafacil.service.StatusConsultaService;
import br.edu.puccampinas.consultafacil.repository.MedicoDoUsuarioRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;


/**
 * Fila de espera (#31) e avisos (#57) pela API, com o MongoDB real e os validadores do banco.
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
    private static final Instant VAGA = Instant.parse("2026-10-26T13:00:00Z");

    private static MongoConnection mongo;
    private final ObjectMapper json = new ObjectMapper();
    private MongoDatabase db;
    private HttpServer server;
    private HttpClient client;
    private String base;
    private Sessoes sessoes;
    private RegistroConsultaService registro;
    private ExecutorService threadsHttp;
    private RelogioAjustavel relogio;
    private volatile CyclicBarrier barreiraDeReserva;

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
        db.getCollection("bloqueios").deleteMany(Filters.eq("medicoId", MEDICO));
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

        relogio = new RelogioAjustavel(AGORA);
        var nomes = new NomesRepository.Mongo(db);
        var fila = new FilaEsperaRepository(db);
        var notificacoes = new NotificacaoRepository(db);
        var especialidades = new EspecialidadeRepository.Mongo(db);
        var medicos = new MedicoRepository(db);
        var pacienteRepo = new PacienteRepository.Mongo(db);
        var pacienteDoUsuario = new PacienteDoUsuarioRepository(db);
        var service = new FilaEsperaService(fila, especialidades, medicos, pacienteRepo, pacienteDoUsuario, nomes, relogio);
        var consultas = new ConsultaRepository(db);
        var historico = new HistoricoProcessor(consultas, new HistoricoRepository.Mongo(db));
        var agendas = new AgendaRepository(db);
        var bloqueios = new BloqueioRepository(db);
        agendas.salvar(MEDICO, new Document("fusoHorario", "America/Sao_Paulo").append("duracaoMinutos", 30)
                .append("jornadas", List.of(new Document("diaSemana", 1).append("faixas",
                        List.of(new Document("inicioMinuto", 600).append("fimMinuto", 660))))));
        var disponibilidade = new DisponibilidadeService(agendas, bloqueios, consultas, relogio);
        var sinalizacao = new SinalizacaoFilaService(fila, notificacoes, consultas, nomes, historico, relogio, disponibilidade);
        registro = new RegistroConsultaService(consultas, historico, relogio, sinalizacao::sinalizar, sinalizacao::consultaAgendada);
        var agendamento = new AgendamentoService(disponibilidade, medicos, especialidades, pacienteRepo, pacienteDoUsuario, registro) {
            @Override public ConsultaResponse agendar(AgendamentoRequest pedido, UsuarioLogado ator) {
                var barreira = barreiraDeReserva;
                if (barreira != null) {
                    try {
                        barreira.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Teste interrompido", e);
                    } catch (java.util.concurrent.BrokenBarrierException | java.util.concurrent.TimeoutException e) {
                        throw new AssertionError("Os dois avisos devem chegar ao agendamento antes da disputa pela vaga", e);
                    }
                }
                return super.agendar(pedido, ator);
            }
        };
        var avisos = new AvisoVagaHandler(new AvisoVagaService(notificacoes, fila, consultas, pacienteDoUsuario,
                sinalizacao, agendamento, relogio));

        sessoes = new Sessoes(Duration.ofMinutes(60));
        var cors = new Cors(List.of());
        var autorizacao = new Autorizacao(new Autenticador(sessoes));
        var medicosDoUsuario = new MedicoDoUsuarioRepository(db);
        var status = new StatusConsultaService(consultas, new AcessoConsulta(pacienteDoUsuario::buscar, medicosDoUsuario::buscar),
                registro, relogio);
        var central = new CentralNotificacoesHandler(new CentralNotificacoesService(notificacoes, consultas,
                pacienteDoUsuario::buscar, medicosDoUsuario::buscar, status, relogio));
        var acessoAgenda = new AcessoAgenda(medicosDoUsuario);
        var afetadas = new ConsultasAfetadas(consultas, nomes);
        var agendaHandler = new AgendaHandler(
                new AgendaService(agendas, medicos, afetadas, relogio, sinalizacao::revisarAgenda), acessoAgenda);
        var bloqueioHandler = new BloqueioHandler(
                new BloqueioService(bloqueios, agendas, medicos, afetadas, sinalizacao::revisarAgenda), acessoAgenda);
        // A montagem preserva os handlers de avisos e central; indicadores ficam
        // ausentes por não participarem deste fluxo de fila.
        var router = Rotas.router(cors, autorizacao,
                new Rotas.Handlers(null, null, agendaHandler, bloqueioHandler, null, null, null, null, avisos, central, null));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        threadsHttp = Executors.newFixedThreadPool(4);
        server.setExecutor(threadsHttp);
        server.createContext("/", router);
        server.createContext("/fila-espera", Rotas.filaEspera(cors, autorizacao, new FilaEsperaHandler(service)));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach void encerrar() {
        if (client != null) client.close();
        if (server != null) server.stop(0);
        if (threadsHttp != null) threadsHttp.shutdownNow();
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
        ObjectId consulta = liberarVaga(P2);

        JsonNode i = json.readTree(enviar("GET", "/fila-espera", paciente(U1), null).body()).get(0);
        assertEquals("SINALIZADA", i.get("status").asText());
        JsonNode s = i.get("ultimaSinalizacao");
        assertEquals(json.readTree(enviar("GET", "/avisos-vaga", paciente(U1), null).body()).get(0).get("id").asText(),
                s.get("notificacaoId").asText());
        assertEquals(consulta.toHexString(), s.get("consultaOrigemId").asText());
        assertEquals(MEDICO.toHexString(), s.get("medicoId").asText());
        assertEquals("Dra. Paula Fila", s.get("medicoNome").asText());
        assertEquals(VAGA.toString(), s.get("dataHora").asText());
    }

    @Test void doisPacientesDisputamAMesmaVagaPeloAviso() throws Exception {
        assertEquals(201, inscrever(U1, ESPECIALIDADE, null).statusCode());
        assertEquals(201, inscrever(U2, ESPECIALIDADE, null).statusCode());
        liberarVaga(P3);
        String token1 = paciente(U1);
        String token2 = paciente(U2);
        JsonNode aviso1 = unicoAviso(token1);
        JsonNode aviso2 = unicoAviso(token2);
        String id1 = aviso1.get("id").asText();
        String id2 = aviso2.get("id").asText();
        assertNotEquals(id1, id2);
        assertEquals("DISPONIVEL", aviso1.get("status").asText());
        assertEquals("DISPONIVEL", aviso2.get("status").asText());
        assertEquals(VAGA.toString(), aviso1.get("validoAte").asText());
        assertTrue(semValor(aviso1, "motivo"));
        assertEquals(MEDICO.toHexString(), aviso1.get("medicoId").asText());
        assertEquals(ESPECIALIDADE.toHexString(), aviso1.get("especialidadeId").asText());
        assertEquals(404, enviar("POST", "/avisos-vaga", token1, Map.of("avisoId", id2)).statusCode());

        barreiraDeReserva = new CyclicBarrier(2);
        var pedido1 = client.sendAsync(requisicao("POST", "/avisos-vaga", token1, Map.of("avisoId", id1)),
                HttpResponse.BodyHandlers.ofString());
        var pedido2 = client.sendAsync(requisicao("POST", "/avisos-vaga", token2, Map.of("avisoId", id2)),
                HttpResponse.BodyHandlers.ofString());
        var resposta1 = pedido1.get(10, TimeUnit.SECONDS);
        var resposta2 = pedido2.get(10, TimeUnit.SECONDS);
        assertEquals(List.of(201, 409), java.util.stream.Stream.of(resposta1, resposta2)
                .map(HttpResponse::statusCode).sorted().toList(), resposta1.body() + " / " + resposta2.body());

        boolean primeiroGanhou = resposta1.statusCode() == 201;
        String idConsulta = json.readTree((primeiroGanhou ? resposta1 : resposta2).body()).get("id").asText();
        String tokenVencedor = primeiroGanhou ? token1 : token2;
        String tokenPerdedor = primeiroGanhou ? token2 : token1;
        JsonNode vencedor = unicoAviso(tokenVencedor);
        JsonNode perdedor = unicoAviso(tokenPerdedor);
        assertEquals("AGENDADO", vencedor.get("status").asText());
        assertEquals(idConsulta, vencedor.get("consultaAgendadaId").asText());
        assertEquals("EXPIRADO", perdedor.get("status").asText());
        assertTrue(semValor(perdedor, "consultaAgendadaId"));
        // #58: a tela explica que outra pessoa ficou com o horário.
        assertEquals("VAGA_OCUPADA", perdedor.get("motivo").asText());
        assertTrue(semValor(vencedor, "motivo"));
        assertEquals("AGUARDANDO", json.readTree(enviar("GET", "/fila-espera", tokenPerdedor, null).body())
                .get(0).get("status").asText());
        assertEquals("CANCELADA", db.getCollection("notificacoes").find(Filters.eq("_id",
                new ObjectId(perdedor.get("id").asText()))).first().getString("status"));
        assertEquals(1, db.getCollection("consultas").countDocuments(Filters.and(
                Filters.eq("medicoId", MEDICO), Filters.eq("inicio", Date.from(VAGA)), Filters.eq("status", "AGENDADA"))));
    }

    @Test void bloqueioParcialExpiraAvisoEDevolvePacienteParaFila() throws Exception {
        String token = paciente(U1);
        JsonNode aviso = prepararAviso(token);
        var adjacente = enviar("POST", "/bloqueios", secretaria(), Map.of("medicoId", MEDICO.toHexString(),
                "inicio", "2026-10-26T09:30", "fim", "2026-10-26T10:00"));
        assertEquals(201, adjacente.statusCode(), adjacente.body());
        assertEquals("DISPONIVEL", unicoAviso(token).get("status").asText());

        // Começa dentro da consulta oferecida. Comparar apenas o início da vaga não detectaria o bloqueio.
        var bloqueio = enviar("POST", "/bloqueios", secretaria(), Map.of("medicoId", MEDICO.toHexString(),
                "inicio", "2026-10-26T10:15", "fim", "2026-10-26T10:45"));
        assertEquals(201, bloqueio.statusCode(), bloqueio.body());

        conferirAvisoExpiradoEInscricaoAguardando(token, aviso);
    }

    @Test void reducaoDaJornadaExpiraAvisoEDevolvePacienteParaFila() throws Exception {
        String token = paciente(U1);
        JsonNode aviso = prepararAviso(token);
        // A nova jornada começa às 10:30, depois do início da vaga oferecida às 10:00.
        var agenda = enviar("PUT", "/agendas?medicoId=" + MEDICO.toHexString(), secretaria(), Map.of(
                "fusoHorario", "America/Sao_Paulo", "duracaoMinutos", 30,
                "jornadas", List.of(Map.of("diaSemana", 1, "faixas",
                        List.of(Map.of("inicioMinuto", 630, "fimMinuto", 660))))));
        assertEquals(200, agenda.statusCode(), agenda.body());

        conferirAvisoExpiradoEInscricaoAguardando(token, aviso);
    }

    private JsonNode prepararAviso(String token) throws Exception {
        assertEquals(201, inscrever(U1, ESPECIALIDADE, null).statusCode());
        liberarVaga(P3);
        JsonNode aviso = unicoAviso(token);
        assertEquals("DISPONIVEL", aviso.get("status").asText());
        return aviso;
    }

    private void conferirAvisoExpiradoEInscricaoAguardando(String token, JsonNode aviso) throws Exception {
        String idAviso = aviso.get("id").asText();
        var notificacao = db.getCollection("notificacoes").find(Filters.eq("_id", new ObjectId(idAviso))).first();
        String idInscricao = notificacao.getObjectId("filaEsperaId").toHexString();
        // A fila já fica disponível para outra oferta, mesmo sem abrir a lista de avisos.
        var respostaFila = enviar("GET", "/fila-espera", token, null);
        assertEquals(200, respostaFila.statusCode(), respostaFila.body());
        JsonNode inscricao = json.readTree(respostaFila.body()).get(0);
        assertEquals(idInscricao, inscricao.get("id").asText());
        assertEquals("AGUARDANDO", inscricao.get("status").asText());
        assertTrue(semValor(inscricao, "ultimaSinalizacao"));

        JsonNode expirado = unicoAviso(token);
        assertEquals(idAviso, expirado.get("id").asText());
        assertEquals("EXPIRADO", expirado.get("status").asText());
        // #58: a agenda mudou e o horário deixou de ser oferecido, sem outra consulta no lugar.
        assertEquals("OPORTUNIDADE_DESATUALIZADA", expirado.get("motivo").asText());
        assertEquals("CANCELADA", notificacao.getString("status"));
        assertEquals(409, enviar("POST", "/avisos-vaga", token, Map.of("avisoId", idAviso)).statusCode());
        assertEquals(0, db.getCollection("consultas").countDocuments(Filters.and(
                Filters.eq("medicoId", MEDICO), Filters.eq("inicio", Date.from(VAGA)), Filters.eq("status", "AGENDADA"))));
    }

    @Test void avisoExpiraAoChegarNoHorarioDaVaga() throws Exception {
        assertEquals(201, inscrever(U1, ESPECIALIDADE, null).statusCode());
        liberarVaga(P3);
        String token = paciente(U1);
        String id = unicoAviso(token).get("id").asText();

        relogio.avancar(Duration.between(AGORA, VAGA));

        JsonNode vencido = unicoAviso(token);
        assertEquals("EXPIRADO", vencido.get("status").asText());
        assertEquals("PRAZO_ENCERRADO", vencido.get("motivo").asText());
        assertEquals(409, enviar("POST", "/avisos-vaga", token, Map.of("avisoId", id)).statusCode());
    }

    // ---- apoio ----

    @Test void lembreteReprocessadoApareceUmaVezEConfirmaPresencaUmaVez() throws Exception {
        var sec = new UsuarioLogado(new ObjectId().toHexString(), "Secretaria", Perfil.SECRETARIA_ADMIN);
        var consulta = registro.criar(new Document("pacienteId", P1).append("medicoId", MEDICO)
                .append("especialidadeId", ESPECIALIDADE).append("inicio", Date.from(VAGA))
                .append("fim", Date.from(VAGA.plusSeconds(1800))), sec).getObjectId("_id");
        var id = new ObjectId();
        var lembrete = new Document("_id", id).append("destinatarioPerfil", "PACIENTE").append("destinatarioId", P1)
                .append("tipo", "LEMBRETE_CONSULTA").append("canal", "SISTEMA").append("simulada", true)
                .append("consultaId", consulta).append("filaEsperaId", null).append("statusConsulta", "AGENDADA")
                .append("antecedenciaHoras", 24).append("titulo", "Lembrete").append("mensagem", "Confirme sua presença.")
                .append("dadosConsulta", new Document("dataHora", Date.from(VAGA)).append("medicoNome", "Dra. Paula"))
                .append("status", "ENVIADA").append("agendadaPara", Date.from(AGORA)).append("enviadaEm", Date.from(AGORA))
                .append("tentativas", 1).append("erro", null).append("criadoEm", Date.from(AGORA));
        var repo = new NotificacaoRepository(db);
        assertTrue(repo.inserirSeNova(lembrete));
        assertFalse(repo.inserirSeNova(new Document(lembrete).append("_id", new ObjectId())));
        var dono = paciente(U1);
        var pedido = Map.of("notificacaoId", id.toHexString());
        var central = json.readTree(enviar("GET", "/notificacoes", dono, null).body());
        assertEquals(1, central.size());
        assertFalse(central.get(0).get("lida").asBoolean());
        assertTrue(central.get(0).get("podeConfirmar").asBoolean());
        assertEquals(0, json.readTree(enviar("GET", "/notificacoes", paciente(U2), null).body()).size());
        assertEquals(404, enviar("POST", "/notificacoes/confirmar", paciente(U2), pedido).statusCode());
        assertEquals(404, enviar("POST", "/notificacoes/lida", paciente(U2), pedido).statusCode());
        assertEquals(204, enviar("POST", "/notificacoes/lida", dono, pedido).statusCode());
        assertEquals("AGENDADA", db.getCollection("consultas").find(Filters.eq("_id", consulta)).first().getString("status"));
        assertEquals(204, enviar("POST", "/notificacoes/confirmar", dono, pedido).statusCode());
        assertEquals(204, enviar("POST", "/notificacoes/confirmar", dono, pedido).statusCode());
        central = json.readTree(enviar("GET", "/notificacoes", dono, null).body());
        assertEquals(1, central.size());
        assertTrue(central.get(0).get("lida").asBoolean());
        assertTrue(central.get(0).get("presencaConfirmada").asBoolean());
        assertEquals("CONFIRMADA", db.getCollection("consultas").find(Filters.eq("_id", consulta)).first().getString("status"));
        assertEquals(1, db.getCollection("historico").countDocuments(new Document("consultaId", consulta).append("evento", "CONFIRMACAO")));
    }

    /**
     * #38: depois do cancelamento, o lembrete e a confirmação já entregues continuam na central, mas
     * marcados como de consulta cancelada; o próprio aviso de cancelamento não recebe a marca.
     */
    @Test void centralMarcaAvisosDeConsultaCancelada() throws Exception {
        var sec = new UsuarioLogado(new ObjectId().toHexString(), "Secretaria", Perfil.SECRETARIA_ADMIN);
        var consulta = registro.criar(new Document("pacienteId", P1).append("medicoId", MEDICO)
                .append("especialidadeId", ESPECIALIDADE).append("inicio", Date.from(VAGA))
                .append("fim", Date.from(VAGA.plusSeconds(1800))), sec).getObjectId("_id");
        var repo = new NotificacaoRepository(db);
        for (String tipo : List.of("CONFIRMACAO_AGENDAMENTO", "LEMBRETE_CONSULTA", "CANCELAMENTO_CONSULTA")) {
            assertTrue(repo.inserirSeNova(new Document("_id", new ObjectId()).append("destinatarioPerfil", "PACIENTE")
                    .append("destinatarioId", P1).append("tipo", tipo).append("canal", "SISTEMA").append("simulada", true)
                    .append("consultaId", consulta).append("filaEsperaId", null)
                    .append("statusConsulta", tipo.startsWith("CANCEL") ? "CANCELADA" : "AGENDADA")
                    .append("antecedenciaHoras", tipo.startsWith("LEMBRETE") ? 24 : null).append("titulo", tipo)
                    .append("mensagem", "Mensagem " + tipo)
                    .append("dadosConsulta", new Document("dataHora", Date.from(VAGA)).append("medicoNome", "Dra. Paula"))
                    .append("status", "ENVIADA").append("agendadaPara", Date.from(AGORA)).append("enviadaEm", Date.from(AGORA))
                    .append("tentativas", 1).append("erro", null).append("criadoEm", Date.from(AGORA))));
        }
        var dono = paciente(U1);
        for (JsonNode item : json.readTree(enviar("GET", "/notificacoes", dono, null).body()))
            assertFalse(item.get("consultaCancelada").asBoolean(), "antes do cancelamento: " + item.get("titulo"));

        db.getCollection("consultas").updateOne(Filters.eq("_id", consulta), Updates.set("status", "CANCELADA"));

        var central = json.readTree(enviar("GET", "/notificacoes", dono, null).body());
        assertEquals(3, central.size(), "os avisos entregues continuam na central");
        for (JsonNode item : central) {
            boolean ehCancelamento = "CANCELAMENTO_CONSULTA".equals(item.get("titulo").asText());
            assertEquals(!ehCancelamento, item.get("consultaCancelada").asBoolean(), item.get("titulo").asText());
            assertFalse(item.get("podeConfirmar").asBoolean(), item.get("titulo").asText());
        }
    }

    @Test void antecedenciasOuCanaisDistintosSaoPermitidos() {
        // O índice uq_notif_idempotencia deve bloquear apenas notificações
        // com a mesma consulta, tipo, destinatário, canal e antecedência.
        ObjectId consultaId = new ObjectId();
        NotificacaoRepository repo = new NotificacaoRepository(db);
        Document base = new Document("destinatarioPerfil", "PACIENTE")
                .append("destinatarioId", P1)
                .append("tipo", "LEMBRETE_CONSULTA")
                .append("canal", "SISTEMA")
                .append("simulada", true)
                .append("consultaId", consultaId)
                .append("filaEsperaId", null)
                .append("statusConsulta", "AGENDADA")
                .append("antecedenciaHoras", 24)
                .append("titulo", "Lembrete")
                .append("mensagem", "Confirme sua presença.")
                .append("dadosConsulta", new Document("dataHora", Date.from(VAGA))
                        .append("medicoNome", "Dra. Paula"))
                .append("status", "AGENDADA")
                .append("agendadaPara", Date.from(VAGA.minus(Duration.ofHours(24))))
                .append("enviadaEm", null)
                .append("tentativas", 0)
                .append("erro", null)
                .append("criadoEm", Date.from(AGORA));

        Document lembrete24h = new Document(base).append("_id", new ObjectId());
        Document lembrete48h = new Document(base).append("_id", new ObjectId())
                .append("antecedenciaHoras", 48)
                .append("agendadaPara", Date.from(VAGA.minus(Duration.ofHours(48))));
        Document lembreteEmail = new Document(base).append("_id", new ObjectId())
                .append("canal", "EMAIL_TESTE");

        assertTrue(repo.inserirSeNova(lembrete24h));
        assertFalse(repo.inserirSeNova(new Document(lembrete24h).append("_id", new ObjectId())));
        assertTrue(repo.inserirSeNova(lembrete48h));
        assertFalse(repo.inserirSeNova(new Document(lembrete48h).append("_id", new ObjectId())));
        assertTrue(repo.inserirSeNova(lembreteEmail));
        assertFalse(repo.inserirSeNova(new Document(lembreteEmail).append("_id", new ObjectId())));

        assertEquals(3, db.getCollection("notificacoes")
                .countDocuments(Filters.eq("consultaId", consultaId)));
    }

    private ObjectId liberarVaga(ObjectId pacienteId) {
        UsuarioLogado sec = new UsuarioLogado(new ObjectId().toHexString(), "Secretaria", Perfil.SECRETARIA_ADMIN);
        ObjectId consulta = registro.criar(new Document("pacienteId", pacienteId).append("medicoId", MEDICO)
                .append("especialidadeId", ESPECIALIDADE).append("inicio", Date.from(VAGA))
                .append("fim", Date.from(VAGA.plus(Duration.ofMinutes(30)))), sec).getObjectId("_id");
        registro.alterar(consulta, new Document("status", "CANCELADA"), sec);
        return consulta;
    }

    private JsonNode unicoAviso(String token) throws Exception {
        var resposta = enviar("GET", "/avisos-vaga", token, null);
        assertEquals(200, resposta.statusCode(), resposta.body());
        JsonNode avisos = json.readTree(resposta.body());
        assertEquals(1, avisos.size());
        return avisos.get(0);
    }

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
        return client.send(requisicao(metodo, rota, token, corpo), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest requisicao(String metodo, String rota, String token, Map<String, ?> corpo) throws Exception {
        var body = corpo == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo));
        return HttpRequest.newBuilder(URI.create(base + rota)).method(metodo, body)
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json").build();
    }

    private void salvar(String colecao, Document d) {
        db.getCollection(colecao).replaceOne(Filters.eq("_id", d.get("_id")), d, new ReplaceOptions().upsert(true));
    }
}
