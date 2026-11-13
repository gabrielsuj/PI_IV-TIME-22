package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.Rotas;
import br.edu.puccampinas.consultafacil.api.handler.ConsultaHandler;
import br.edu.puccampinas.consultafacil.api.handler.VagasHandler;
import br.edu.puccampinas.consultafacil.api.middleware.Cors;
import br.edu.puccampinas.consultafacil.config.MongoConnection;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.repository.AgendaRepository;
import br.edu.puccampinas.consultafacil.repository.BloqueioRepository;
import br.edu.puccampinas.consultafacil.repository.ConsultaRepository;
import br.edu.puccampinas.consultafacil.repository.EspecialidadeRepository;
import br.edu.puccampinas.consultafacil.repository.HistoricoRepository;
import br.edu.puccampinas.consultafacil.repository.MedicoRepository;
import br.edu.puccampinas.consultafacil.repository.PacienteDoUsuarioRepository;
import br.edu.puccampinas.consultafacil.repository.PacienteRepository;
import br.edu.puccampinas.consultafacil.security.Autenticador;
import br.edu.puccampinas.consultafacil.security.Autorizacao;
import br.edu.puccampinas.consultafacil.security.Sessoes;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import br.edu.puccampinas.consultafacil.service.AgendamentoService;
import br.edu.puccampinas.consultafacil.service.DisponibilidadeService;
import br.edu.puccampinas.consultafacil.service.HistoricoProcessor;
import br.edu.puccampinas.consultafacil.service.RegistroConsultaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Garantias de reserva pela API de verdade (#55): POST /consultas e GET /vagas com o MongoDB real.
 *
 * O servidor de teste processa as requisições em várias threads ao mesmo tempo (o App usa o executor
 * padrão, de uma thread). Assim os pedidos simultâneos disputam a vaga de fato e o teste prova que a
 * garantia vem dos índices únicos parciais do banco, e não da ordem de chegada.
 *
 * Usa o banco consultafacil_test (scripts/recriar-banco.sh consultafacil_test) e só apaga os próprios dados.
 * Relógio parado em 20/10/2026; consultas na segunda 26/10. Horários em UTC: 09:00 em São Paulo = 12:00Z.
 */
class ReservasConcorrenciaIntegrationTest {
    private static final ObjectId ESPECIALIDADE = new ObjectId("b00000000000000000000001");
    private static final ObjectId MEDICO_30 = new ObjectId("b10000000000000000000001"); // seg 08–12, 30 min
    private static final ObjectId MEDICO_20 = new ObjectId("b10000000000000000000002"); // seg 08–12, 20 min
    private static final List<ObjectId> PACIENTES = new ArrayList<>();
    private static final Instant AGORA = Instant.parse("2026-10-20T12:00:00Z");
    private static final UsuarioLogado SECRETARIA =
            new UsuarioLogado(new ObjectId().toHexString(), "Secretaria teste", Perfil.SECRETARIA_ADMIN);
    private static final HttpHandler NAO_USADO = ex -> { throw new IllegalStateException("rota não usada neste teste"); };

    static {
        for (int i = 1; i <= 12; i++) PACIENTES.add(new ObjectId(String.format("b4%022x", i)));
    }

    private static MongoConnection mongo;
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private ExecutorService threadsDoServidor;
    private HttpClient client;
    private String base;
    private String token;
    private RegistroConsultaService registro;

    @BeforeAll static void conectar() {
        mongo = new MongoConnection("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000", "consultafacil_test");
    }

    @AfterAll static void fechar() { mongo.close(); }

    @BeforeEach void preparar() throws Exception {
        MongoDatabase db = mongo.database();
        var meus = Filters.in("medicoId", MEDICO_30, MEDICO_20);
        db.getCollection("consultas").deleteMany(meus);
        db.getCollection("historico").deleteMany(meus);
        db.getCollection("agendas").deleteMany(meus);
        db.getCollection("bloqueios").deleteMany(meus);
        salvar(db, "especialidades", new Document("_id", ESPECIALIDADE)
                .append("codigo", "teste-reservas").append("nome", "Clínica Teste").append("ativa", true));
        int crm = 9800001;
        for (ObjectId medico : List.of(MEDICO_30, MEDICO_20))
            salvar(db, "medicos", new Document("_id", medico).append("nome", "Médico Teste " + crm)
                    .append("crmNumero", String.valueOf(crm++)).append("crmUf", "RR")
                    .append("especialidadeIds", List.of(ESPECIALIDADE)).append("ativo", true));
        for (int i = 0; i < PACIENTES.size(); i++)
            salvar(db, "pacientes", new Document("_id", PACIENTES.get(i)).append("nome", "Paciente Teste " + (i + 1))
                    .append("cpf", String.format("980000000%02d", i + 1)).append("email", "reservas" + (i + 1) + "@consultafacil.test"));
        agenda(db, MEDICO_30, 30);
        agenda(db, MEDICO_20, 20);

        Clock relogio = Clock.fixed(AGORA, ZoneOffset.UTC);
        var consultas = new ConsultaRepository(db);
        registro = new RegistroConsultaService(consultas, new HistoricoProcessor(consultas, new HistoricoRepository.Mongo(db)), relogio);
        var agendamento = new AgendamentoService(
                new DisponibilidadeService(new AgendaRepository(db), new BloqueioRepository(db), consultas, relogio),
                new MedicoRepository(db), new EspecialidadeRepository.Mongo(db),
                new PacienteRepository.Mongo(db), new PacienteDoUsuarioRepository(db), registro);

        var sessoes = new Sessoes(Duration.ofMinutes(60));
        token = sessoes.criar(SECRETARIA).token();
        var autz = new Autorizacao(new Autenticador(sessoes));
        var router = Rotas.router(new Cors(List.of()), autz, new Rotas.Handlers(NAO_USADO, NAO_USADO, NAO_USADO,
                NAO_USADO, NAO_USADO, new VagasHandler(agendamento), new ConsultaHandler(agendamento), NAO_USADO));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        threadsDoServidor = Executors.newFixedThreadPool(16);
        server.setExecutor(threadsDoServidor);
        server.createContext("/", router);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach void encerrar() {
        if (client != null) client.close();
        if (server != null) server.stop(0);
        if (threadsDoServidor != null) threadsDoServidor.shutdownNow();
    }

    // ---------------------------------------------------------------- concorrência

    /** Quadro 4: dois (aqui, dez) usuários escolhem a mesma vaga ao mesmo tempo. Só um é confirmado. */
    @Test void deveConfirmarApenasUmAgendamentoParaMesmaVaga() throws Exception {
        List<String> pedidos = new ArrayList<>();
        for (int i = 0; i < 10; i++) pedidos.add(corpo(PACIENTES.get(i), MEDICO_30, "12:00", "12:30"));

        List<Integer> status = simultaneos(pedidos);

        assertEquals(1, Collections.frequency(status, 201), "exatamente um agendamento confirmado: " + status);
        assertEquals(9, Collections.frequency(status, 409), "os demais recebem 409: " + status);
        assertEquals(1, ativas(Filters.eq("medicoId", MEDICO_30)), "uma única consulta ativa no banco");
        assertEquals(1, mongo.database().getCollection("historico").countDocuments(Filters.eq("medicoId", MEDICO_30)),
                "um único evento de criação no histórico");
        assertFalse(iniciosDasVagas(MEDICO_30).contains("2026-10-26T12:00:00Z"), "a vaga saiu da busca");
    }

    /** O mesmo paciente tenta dois médicos no mesmo horário ao mesmo tempo: o índice do paciente barra um deles. */
    @Test void mesmoPacienteNaoReservaDoisMedicosNoMesmoHorarioAoMesmoTempo() throws Exception {
        ObjectId paciente = PACIENTES.get(0);
        List<Integer> status = simultaneos(List.of(
                corpo(paciente, MEDICO_30, "12:00", "12:30"),
                corpo(paciente, MEDICO_20, "12:00", "12:20")));

        assertEquals(List.of(201, 409), status.stream().sorted().toList());
        assertEquals(1, ativas(Filters.eq("pacienteId", paciente)));
    }

    /** Pedidos simultâneos para vagas diferentes não podem gerar conflito falso. */
    @Test void pedidosSimultaneosEmVagasDiferentesSaoTodosAceitos() throws Exception {
        List<String> pedidos = new ArrayList<>();
        String[] inicios = {"11:00", "11:30", "12:00", "12:30", "13:00", "13:30", "14:00", "14:30"};
        for (int i = 0; i < inicios.length; i++)
            pedidos.add(corpo(PACIENTES.get(i), MEDICO_30, inicios[i], somar30(inicios[i])));

        assertEquals(Collections.nCopies(8, 201), simultaneos(pedidos));
        assertEquals(8, ativas(Filters.eq("medicoId", MEDICO_30)));
        assertTrue(iniciosDasVagas(MEDICO_30).isEmpty(), "a manhã inteira ficou ocupada");
    }

    // ---------------------------------------------------------------- integração

    /** Quadro 4: médico já ocupado. Horário reservado ou fora da grade da agenda é recusado. */
    @Test void deveRecusarSobreposicaoDeHorario() throws Exception {
        assertEquals(201, post(corpo(PACIENTES.get(0), MEDICO_30, "12:00", "12:30")).statusCode());

        assertEquals(409, post(corpo(PACIENTES.get(1), MEDICO_30, "12:00", "12:30")).statusCode(), "mesmo horário");
        assertEquals(409, post(corpo(PACIENTES.get(1), MEDICO_30, "12:15", "12:45")).statusCode(), "sobreposto, fora da grade");
        assertEquals(201, post(corpo(PACIENTES.get(1), MEDICO_30, "12:30", "13:00")).statusCode(), "vaga seguinte continua livre");
    }

    /** Quadro 4: paciente já ocupado em outro médico, em horário que se sobrepõe com início diferente. */
    @Disabled("Pendente #25: hoje só o mesmo instante de início é barrado (índice uk_consulta_paciente_inicio)")
    @Test void pacienteNaoPodeTerConsultasSobrepostasEmMedicosDiferentes() throws Exception {
        ObjectId paciente = PACIENTES.get(0);
        assertEquals(201, post(corpo(paciente, MEDICO_30, "12:00", "12:30")).statusCode()); // 09:00–09:30
        assertEquals(409, post(corpo(paciente, MEDICO_20, "12:20", "12:40")).statusCode()); // 09:20–09:40
    }

    /** Cancelamento libera a vaga para outra pessoa e registra os dois eventos no histórico. */
    @Test void cancelamentoLiberaAVagaParaOutroPaciente() throws Exception {
        var primeira = post(corpo(PACIENTES.get(0), MEDICO_30, "13:00", "13:30"));
        assertEquals(201, primeira.statusCode());
        ObjectId cancelada = new ObjectId(json.readTree(primeira.body()).get("id").asText());

        // A rota de cancelamento é a #27; aqui o cancelamento usa o mesmo serviço que ela vai usar.
        registro.alterar(cancelada, new Document("status", "CANCELADA").append("motivoCancelamento", "Paciente solicitou remarcação"), SECRETARIA);

        assertTrue(iniciosDasVagas(MEDICO_30).contains("2026-10-26T13:00:00Z"), "a vaga voltou para a busca");
        var segunda = post(corpo(PACIENTES.get(1), MEDICO_30, "13:00", "13:30"));
        assertEquals(201, segunda.statusCode());
        ObjectId nova = new ObjectId(json.readTree(segunda.body()).get("id").asText());

        assertEquals(List.of("CRIACAO", "CANCELAMENTO"), eventos(cancelada));
        assertEquals(List.of("CRIACAO"), eventos(nova));
    }

    // ---------------------------------------------------------------- apoio

    private static void salvar(MongoDatabase db, String colecao, Document d) {
        db.getCollection(colecao).replaceOne(Filters.eq("_id", d.get("_id")), d, new ReplaceOptions().upsert(true));
    }

    private static void agenda(MongoDatabase db, ObjectId medico, int duracao) {
        db.getCollection("agendas").insertOne(new Document("medicoId", medico)
                .append("fusoHorario", "America/Sao_Paulo").append("duracaoMinutos", duracao)
                .append("jornadas", List.of(new Document("diaSemana", 1).append("faixas",
                        List.of(new Document("inicioMinuto", 480).append("fimMinuto", 720))))));
    }

    /** Horários em UTC no dia 26/10 ("12:00" = 09:00 em São Paulo). */
    private static String corpo(ObjectId paciente, ObjectId medico, String inicioUtc, String fimUtc) {
        return "{\"pacienteId\":\"" + paciente.toHexString() + "\",\"medicoId\":\"" + medico.toHexString()
                + "\",\"especialidadeId\":\"" + ESPECIALIDADE.toHexString()
                + "\",\"inicio\":\"2026-10-26T" + inicioUtc + ":00Z\",\"fim\":\"2026-10-26T" + fimUtc + ":00Z\"}";
    }

    private static String somar30(String hora) {
        int m = Integer.parseInt(hora.substring(0, 2)) * 60 + Integer.parseInt(hora.substring(3)) + 30;
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    private HttpResponse<String> post(String corpo) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/consultas"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Dispara todos os pedidos juntos (largada única) e devolve os status na mesma ordem. */
    private List<Integer> simultaneos(List<String> corpos) throws Exception {
        ExecutorService clientes = Executors.newFixedThreadPool(corpos.size());
        try {
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<Integer>> respostas = new ArrayList<>();
            for (String c : corpos) respostas.add(clientes.submit(() -> { largada.await(); return post(c).statusCode(); }));
            largada.countDown();
            List<Integer> status = new ArrayList<>();
            for (Future<Integer> r : respostas) status.add(r.get(30, TimeUnit.SECONDS));
            return status;
        } finally {
            clientes.shutdownNow();
        }
    }

    private List<String> iniciosDasVagas(ObjectId medico) throws Exception {
        var r = client.send(HttpRequest.newBuilder(URI.create(base + "/vagas?medicoId=" + medico.toHexString()
                        + "&especialidadeId=" + ESPECIALIDADE.toHexString() + "&data=2026-10-26"))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        List<String> inicios = new ArrayList<>();
        for (JsonNode v : json.readTree(r.body()).get("horarios")) inicios.add(v.get("inicio").asText());
        return inicios;
    }

    private static long ativas(org.bson.conversions.Bson filtro) {
        return mongo.database().getCollection("consultas").countDocuments(
                Filters.and(filtro, Filters.in("status", "AGENDADA", "CONFIRMADA")));
    }

    private static List<String> eventos(ObjectId consulta) {
        List<String> eventos = new ArrayList<>();
        mongo.database().getCollection("historico").find(Filters.eq("consultaId", consulta))
                .sort(new Document("sequencia", 1)).forEach(e -> eventos.add(e.getString("evento")));
        return eventos;
    }
}
