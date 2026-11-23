/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.config.MongoConnection;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.repository.*;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import br.edu.puccampinas.consultafacil.service.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.mongodb.client.model.Filters.*;
import static org.junit.jupiter.api.Assertions.*;


/** Opt-in: HISTORICO_MONGO_URI; usa apenas consultafacil_historico_test com os schemas aplicados. */
// A fixture usa o esquema real para conferir recuperação e repetição de eventos sem
// duplicar a sequência do histórico.
class HistoricoPersistenceIntegrationTest {
    private static MongoConnection mongo;
    private final List<ObjectId> criadas = new ArrayList<>();
    private ConsultaRepository consultas;
    private HistoricoRepository.Mongo historico;
    private final UsuarioLogado ator = new UsuarioLogado(new ObjectId().toHexString(), "Usuário de teste", Perfil.SECRETARIA_ADMIN);

    @BeforeAll static void conectar() {
        String uri = System.getenv("HISTORICO_MONGO_URI");
        Assumptions.assumeTrue(uri != null && !uri.isBlank(), "Defina HISTORICO_MONGO_URI para executar com MongoDB real");
        mongo = new MongoConnection(uri, "consultafacil_historico_test");
        mongo.ping();
        Document info = mongo.database().listCollections().filter(eq("name", "historico")).first();
        assertNotNull(info, "Aplique database/init antes dos testes");
        assertTrue(info.get("options", Document.class).containsKey("validator"), "Validador de histórico obrigatório neste teste");
    }
    @AfterAll static void fechar() { if (mongo != null) mongo.close(); }
    @BeforeEach void preparar() {
        consultas = new ConsultaRepository(mongo.database());
        historico = new HistoricoRepository.Mongo(mongo.database());
    }
    @AfterEach void limparSomenteRegistrosCriadosPeloTeste() {
        if (!criadas.isEmpty()) {
            mongo.database().getCollection("historico").deleteMany(in("consultaId", criadas));
            mongo.database().getCollection("consultas").deleteMany(in("_id", criadas));
        }
    }
    private RegistroConsultaService service(HistoricoRepository destino) {
        return new RegistroConsultaService(consultas, new HistoricoProcessor(consultas, destino), Clock.systemUTC());
    }
    private Document dados() {
        return new Document("pacienteId", new ObjectId()).append("medicoId", new ObjectId())
                .append("especialidadeId", new ObjectId()).append("inicio", Date.from(Instant.parse("2026-12-01T12:00:00Z")))
                .append("fim", Date.from(Instant.parse("2026-12-01T12:30:00Z")));
    }
    private ObjectId criarPendente() {
        ObjectId id = service(e -> { throw new IllegalStateException("destino indisponível"); })
                .criar(dados(), ator).getObjectId("_id");
        criadas.add(id); return id;
    }

    @Test void persisteRecuperaEReexecutaSemDuplicarComSchemasReais() {
        ObjectId id = criarPendente();
        Document consulta = consultas.buscar(id).orElseThrow();
        assertEquals(1, consulta.getInteger("versao"));
        Document e = consulta.getList("eventosPendentes", Document.class).getFirst();
        // Simula queda depois de inserir e antes de remover a pendência.
        historico.registrar(e);
        historico.registrar(e);
        new HistoricoProcessor(consultas, historico).processarConsulta(id);
        assertEquals(1, mongo.database().getCollection("historico").countDocuments(eq("consultaId", id)));
        assertTrue(consultas.buscar(id).orElseThrow().getList("eventosPendentes", Document.class).isEmpty());
    }

    @Test void leituraSinalizaPendenciaPersistidaEAcompanhaProjecaoSemModificarConsulta() {
        ObjectId id = criarPendente();
        var acesso = new AcessoConsulta(login -> Optional.empty(), login -> Optional.empty());
        var leitor = new HistoricoConsultaService(consultas, historico, acesso, new NomesRepository.Mongo(mongo.database()));
        Document antes = consultas.buscar(id).orElseThrow();
        var pendente = leitor.listar(id, ator);
        assertTrue(pendente.atualizacaoPendente());
        assertTrue(pendente.eventos().isEmpty());
        assertFalse(pendente.temMais());
        assertEquals(antes, consultas.buscar(id).orElseThrow());
        assertEquals(0, historico.ultimaSequencia(id));

        new HistoricoProcessor(consultas, historico).processarConsulta(id);
        Document projetada = consultas.buscar(id).orElseThrow();
        var atualizada = leitor.listar(id, ator);
        assertFalse(atualizada.atualizacaoPendente());
        assertEquals(1, atualizada.eventos().size());
        assertEquals("CRIACAO", atualizada.eventos().getFirst().evento());
        assertEquals(projetada, consultas.buscar(id).orElseThrow());
        assertEquals(antes.getInteger("versao"), projetada.getInteger("versao"));
    }

    @Test void identidadeDivergenteNaoApagaPendencia() {
        ObjectId id = criarPendente();
        Document e = consultas.buscar(id).orElseThrow().getList("eventosPendentes", Document.class).getFirst();
        historico.registrar(e);
        Document diferente = new Document(e).append("ocorridoEm", new Date(0));
        assertThrows(IllegalStateException.class, () -> historico.registrar(diferente));
        assertEquals(1, consultas.buscar(id).orElseThrow().getList("eventosPendentes", Document.class).size());
    }

    @Test void atualizaLegadoSemVersaoNemArray() {
        Document antigo = dados().append("status", "AGENDADA").append("criadaEm", new Date());
        ObjectId id = consultas.inserir(antigo).getObjectId("_id");
        criadas.add(id);
        service(historico).alterar(id, new Document("status", "CONFIRMADA"), ator);
        Document e = mongo.database().getCollection("historico").find(eq("consultaId", id)).first();
        assertNotNull(e);
        assertEquals("CONFIRMACAO", e.get("evento"));
        assertEquals(1, consultas.buscar(id).orElseThrow().getInteger("versao"));
    }

    @Test void duasAtualizacoesDaMesmaVersaoSomenteUmaPersisteComEvento() throws Exception {
        ObjectId id = criarPendente();
        Document original = consultas.buscar(id).orElseThrow().getList("eventosPendentes", Document.class).getFirst();
        CountDownLatch largada = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> resultados = new ArrayList<>();
            for (String status : List.of("CONFIRMADA", "CANCELADA")) {
                Document evento = new Document(original).append("_id", new ObjectId()).append("sequencia", 2)
                        .append("statusAnterior", "AGENDADA").append("statusNovo", status)
                        .append("evento", status.equals("CONFIRMADA") ? "CONFIRMACAO" : "CANCELAMENTO");
                resultados.add(pool.submit(() -> {
                    largada.await();
                    return consultas.atualizarComEvento(id, 1, new Document("status", status), evento);
                }));
            }
            largada.countDown();
            int sucesso = 0;
            for (var resultado : resultados) if (resultado.get(10, TimeUnit.SECONDS)) sucesso++;
            assertEquals(1, sucesso);
        }
        Document finalizada = consultas.buscar(id).orElseThrow();
        assertEquals(2, finalizada.getInteger("versao"));
        List<Document> fila = finalizada.getList("eventosPendentes", Document.class);
        assertEquals(2, fila.size());
        assertEquals(finalizada.get("status"), fila.get(1).get("statusNovo"));
    }

    @Test void rejeicaoDoBancoNaoAlteraConsultaNemAcrescentaEvento() {
        ObjectId id = criarPendente();
        Document antes = consultas.buscar(id).orElseThrow();
        Document evento = new Document(antes.getList("eventosPendentes", Document.class).getFirst())
                .append("_id", new ObjectId()).append("sequencia", 2);
        assertThrows(RuntimeException.class, () -> consultas.atualizarComEvento(id, 1, new Document("status", "INVALIDO"), evento));
        assertEquals(antes, consultas.buscar(id).orElseThrow());
    }

    /** Produz os eventos reais e os projeta fora de ordem, como pode ocorrer em uma recuperação. */
    private ObjectId projetarTresEventosForaDeOrdem() {
        ObjectId id = criarPendente();
        var registro = service(e -> { throw new IllegalStateException("destino indisponível"); });
        registro.alterar(id, new Document("status", "CONFIRMADA"), ator);
        registro.alterar(id, new Document("status", "CANCELADA"), ator);
        List<Document> fila = consultas.buscar(id).orElseThrow().getList("eventosPendentes", Document.class);
        assertEquals(3, fila.size());
        for (int indice : List.of(2, 0, 1)) historico.registrar(fila.get(indice));
        return id;
    }

    @Test void leituraIsolaConsultaEOrdenaPelaSequenciaSemProcessarPendencias() {
        ObjectId id = projetarTresEventosForaDeOrdem();
        ObjectId outra = criarPendente();
        historico.registrar(consultas.buscar(outra).orElseThrow().getList("eventosPendentes", Document.class).getFirst());
        Document antes = consultas.buscar(id).orElseThrow();

        HistoricoLeituraRepository leitura = historico;
        List<Document> eventos = leitura.listarPorConsulta(id, 0, 100);

        assertEquals(List.of(1, 2, 3), eventos.stream().map(e -> e.getInteger("sequencia")).toList());
        assertEquals(List.of("CRIACAO", "CONFIRMACAO", "CANCELAMENTO"), eventos.stream().map(e -> e.getString("evento")).toList());
        assertTrue(eventos.stream().allMatch(e -> id.equals(e.getObjectId("consultaId"))));
        assertEquals(antes, consultas.buscar(id).orElseThrow(), "A leitura não reconhece nem esvazia pendências");
        assertEquals(3, historico.ultimaSequencia(id));
        // Ler não muda o contrato de inserção idempotente já usado pelo processador.
        historico.registrar(eventos.getFirst());
        assertEquals(3, leitura.listarPorConsulta(id, 0, 100).size());
    }

    @Test void leituraRespeitaCursorExclusivoLimiteEHistoricoVazio() {
        ObjectId id = projetarTresEventosForaDeOrdem();
        assertEquals(List.of(2), historico.listarPorConsulta(id, 1, 1).stream().map(e -> e.getInteger("sequencia")).toList());
        assertEquals(List.of(2, 3), historico.listarPorConsulta(id, 1, 100).stream().map(e -> e.getInteger("sequencia")).toList());
        assertTrue(historico.listarPorConsulta(id, 3, 100).isEmpty());
        assertTrue(historico.listarPorConsulta(new ObjectId(), 0, 100).isEmpty());
    }

    @Test void leituraRecusaParametrosQuePermitemBuscaSemConsultaOuSemLimite() {
        assertThrows(IllegalArgumentException.class, () -> historico.listarPorConsulta(null, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> historico.listarPorConsulta(new ObjectId(), -1, 10));
        for (int limite : List.of(-1, 0, 101))
            assertThrows(IllegalArgumentException.class, () -> historico.listarPorConsulta(new ObjectId(), 0, limite));
    }
}
