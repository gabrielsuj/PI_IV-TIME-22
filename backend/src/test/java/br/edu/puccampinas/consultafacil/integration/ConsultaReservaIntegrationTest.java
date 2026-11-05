package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.config.MongoConnection;
import br.edu.puccampinas.consultafacil.repository.ConsultaRepository;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Requer o MongoDB local com database/init/03-esquema-consultas.js aplicado no consultafacil_test. */
class ConsultaReservaIntegrationTest {
    private static final ObjectId ANA = new ObjectId("100000000000000000000001");
    private static final ObjectId BRUNO = new ObjectId("100000000000000000000002");
    private static final Date INICIO = Date.from(Instant.parse("2026-11-09T12:00:00Z"));  // 09:00 em São Paulo
    private static final Date FIM = Date.from(Instant.parse("2026-11-09T12:30:00Z"));

    private static MongoConnection mongo;
    private MongoCollection<Document> colecao;
    private ConsultaRepository consultas;

    @BeforeAll
    static void conectar() {
        mongo = new MongoConnection("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000", "consultafacil_test");
    }

    @AfterAll
    static void fechar() { mongo.close(); }

    @BeforeEach
    void preparar() {
        colecao = mongo.database().getCollection("consultas");
        colecao.deleteMany(new Document());
        consultas = new ConsultaRepository(mongo.database());
    }

    private static Document consulta(ObjectId paciente, ObjectId medico, Date inicio, Date fim) {
        return new Document("pacienteId", paciente).append("medicoId", medico)
                .append("especialidadeId", new ObjectId("000000000000000000000001"))
                .append("inicio", inicio).append("fim", fim)
                .append("status", "AGENDADA").append("criadaEm", new Date());
    }

    private static void assertStatus(int esperado, Runnable acao) {
        ApiException e = assertThrows(ApiException.class, acao::run);
        assertEquals(esperado, e.status());
    }

    @Test
    void deveConfirmarApenasUmAgendamentoParaMesmaVaga() throws Exception {
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Integer>> resultados = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            ObjectId paciente = new ObjectId();               // 10 pacientes diferentes, mesma vaga
            resultados.add(pool.submit(() -> {
                largada.await();                              // todos disparam juntos
                try {
                    consultas.inserir(consulta(paciente, ANA, INICIO, FIM));
                    return 201;
                } catch (ApiException e) {
                    return e.status();
                }
            }));
        }
        largada.countDown();

        int confirmados = 0, recusados = 0;
        for (Future<Integer> r : resultados) {
            int s = r.get(10, TimeUnit.SECONDS);
            if (s == 201) confirmados++;
            else if (s == 409) recusados++;
        }
        pool.shutdown();
        assertEquals(1, confirmados);
        assertEquals(n - 1, recusados);
    }

    @Test
    void deveRecusarMesmoPacienteNoMesmoHorario() {
        ObjectId paciente = new ObjectId();
        consultas.inserir(consulta(paciente, ANA, INICIO, FIM));
        assertStatus(409, () -> consultas.inserir(consulta(paciente, BRUNO, INICIO, FIM)));
    }

    @Test
    void devePermitirReservarVagaDeConsultaCancelada() {
        Document primeira = consultas.inserir(consulta(new ObjectId(), ANA, INICIO, FIM));
        colecao.updateOne(Filters.eq("_id", primeira.getObjectId("_id")), Updates.set("status", "CANCELADA"));
        assertDoesNotThrow(() -> consultas.inserir(consulta(new ObjectId(), ANA, INICIO, FIM)));
    }

    @Test
    void deveRecusarStatusInvalido() {
        Document invalida = consulta(new ObjectId(), ANA, INICIO, FIM).append("status", "XYZ");
        assertStatus(400, () -> consultas.inserir(invalida));
    }

    @Test
    void deveListarSomenteAtivasNoPeriodo() {
        consultas.inserir(consulta(new ObjectId(), ANA, INICIO, FIM));
        Document cancelada = consulta(new ObjectId(), ANA,
                Date.from(Instant.parse("2026-11-09T13:00:00Z")), Date.from(Instant.parse("2026-11-09T13:30:00Z")))
                .append("status", "CANCELADA");
        consultas.inserir(cancelada);

        List<Document> ativas = consultas.ativasNoPeriodo(ANA,
                Date.from(Instant.parse("2026-11-09T00:00:00Z")), Date.from(Instant.parse("2026-11-10T00:00:00Z")));
        assertEquals(1, ativas.size());
    }
}