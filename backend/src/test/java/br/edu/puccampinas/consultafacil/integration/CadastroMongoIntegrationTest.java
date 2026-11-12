package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.*;
import br.edu.puccampinas.consultafacil.config.MongoConnection;
import br.edu.puccampinas.consultafacil.repository.*;
import br.edu.puccampinas.consultafacil.service.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static com.mongodb.client.model.Filters.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in: -DmongoTests=true. Banco fixo consultafacil_test; limpa somente IDs criados pelo teste. */
class CadastroMongoIntegrationTest {
    private MongoConnection mongo;
    private MedicoRepository medicoRepo;
    private EspecialidadeService especialidades;
    private MedicoService medicos;
    private final List<ObjectId> medicoIds = new ArrayList<>();
    private final List<ObjectId> especialidadeIds = new ArrayList<>();

    @BeforeEach void preparar() {
        assumeTrue(Boolean.getBoolean("mongoTests"), "Habilite -DmongoTests=true com MongoDB de testes disponível");
        mongo = new MongoConnection("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000", "consultafacil_test");
        mongo.ping();
        var db = mongo.database();
        // Não criar schemas ou índices automaticamente: testar a modelagem real instalada.
        for (String colecao : List.of("medicos", "especialidades")) {
            Document info = db.listCollections().filter(eq("name", colecao)).first();
            assertNotNull(info, "Coleção de teste ausente: " + colecao);
            assertTrue(info.get("options", Document.class).containsKey("validator"), "Validador ausente: " + colecao);
        }
        assertTrue(db.getCollection("medicos").listIndexes().into(new ArrayList<>()).stream()
                .anyMatch(i -> "uk_medico_crm".equals(i.getString("name")) && i.getBoolean("unique", false)));
        assertTrue(db.getCollection("especialidades").listIndexes().into(new ArrayList<>()).stream()
                .anyMatch(i -> "uk_especialidade_codigo".equals(i.getString("name")) && i.getBoolean("unique", false)));
        medicoRepo = new MedicoRepository(db);
        var especialidadeRepo = new EspecialidadeRepository.Mongo(db);
        medicos = new MedicoService(medicoRepo, especialidadeRepo);
        especialidades = new EspecialidadeService(especialidadeRepo, medicoRepo);
    }

    @AfterEach void limpar() {
        if (mongo == null) return;
        try {
            if (!medicoIds.isEmpty()) mongo.database().getCollection("medicos").deleteMany(in("_id", medicoIds));
            if (!especialidadeIds.isEmpty()) mongo.database().getCollection("especialidades").deleteMany(in("_id", especialidadeIds));
        } finally { mongo.close(); }
    }

    private EspecialidadeResponse especialidade() {
        var e = especialidades.cadastrar(new EspecialidadeRequest("teste-" + new ObjectId(), "Teste da Parte 1", true));
        especialidadeIds.add(new ObjectId(e.id()));
        return e;
    }

    private MedicoResponse medico(EspecialidadeResponse e, String crm, String uf) {
        var m = medicos.cadastrar(new MedicoRequest("Médico de teste", crm, uf, List.of(e.id()), true));
        medicoIds.add(new ObjectId(m.id()));
        return m;
    }

    private static String crm() { return String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L)); }

    @Test void persisteTiposPreservaLoginEVinculosEDesativaSemApagar() {
        var e = especialidade();
        var m = medico(e, crm(), "SP");
        ObjectId mid = new ObjectId(m.id());
        ObjectId usuarioId = new ObjectId();
        mongo.database().getCollection("medicos").updateOne(eq("_id", mid), new Document("$set", new Document("usuarioId", usuarioId)));
        assertTrue(medicoRepo.existeAtivo(mid));
        assertTrue(medicos.listar().stream().anyMatch(v -> v.id().equals(m.id())));
        assertTrue(especialidades.listar().stream().anyMatch(v -> v.id().equals(e.id())));
        var bruto = mongo.database().getCollection("medicos").find(eq("_id", mid)).first();
        assertEquals(m.crmNumero(), bruto.getString("crmNumero"));
        assertEquals(List.of(new ObjectId(e.id())), bruto.getList("especialidadeIds", ObjectId.class));
        assertEquals(409, assertThrows(ApiException.class, () -> especialidades.atualizar(e.id(),
                new EspecialidadeRequest(e.codigo(), e.nome(), false))).status());
        medicos.atualizar(m.id(), new MedicoRequest("Nome atualizado", m.crmNumero(), "SP", m.especialidadeIds(), false));
        assertFalse(medicoRepo.existeAtivo(mid));
        assertEquals(usuarioId, mongo.database().getCollection("medicos").find(eq("_id", mid)).first().getObjectId("usuarioId"));
        especialidades.atualizar(e.id(), new EspecialidadeRequest(e.codigo(), "Nome atualizado", false));
        assertFalse(especialidades.consultar(e.id()).ativa());
        assertEquals(List.of(e.id()), medicos.consultar(m.id()).especialidadeIds());
    }

    @Test void indicesGarantem409InclusiveNaDisputaConcorrentePeloCrm() throws Exception {
        var e = especialidade();
        assertEquals(409, assertThrows(ApiException.class, () -> especialidades.cadastrar(
                new EspecialidadeRequest(e.codigo(), e.nome(), true))).status());
        String crm = crm();
        var inicio = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Object> salvar = () -> {
                inicio.await();
                try { return medicos.cadastrar(new MedicoRequest("Teste concorrente", crm, "SP", List.of(e.id()), true)); }
                catch (ApiException ex) { return ex; }
            };
            var a = executor.submit(salvar);
            var b = executor.submit(salvar);
            inicio.countDown();
            var resultados = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            for (Object resultado : resultados)
                if (resultado instanceof MedicoResponse m) medicoIds.add(new ObjectId(m.id()));
            assertEquals(1, resultados.stream().filter(MedicoResponse.class::isInstance).count());
            assertEquals(1, resultados.stream().filter(v -> v instanceof ApiException ex && ex.status() == 409).count());
        }
        // O mesmo número é permitido em outra UF; edição para o par ocupado deve retornar 409.
        var outro = medico(e, crm, "RJ");
        assertEquals(409, assertThrows(ApiException.class, () -> medicos.atualizar(outro.id(),
                new MedicoRequest(outro.nome(), crm, "SP", outro.especialidadeIds(), true))).status());
        assertEquals("RJ", medicos.consultar(outro.id()).crmUf());
        var outra = especialidade();
        assertEquals(409, assertThrows(ApiException.class, () -> especialidades.atualizar(outra.id(),
                new EspecialidadeRequest(e.codigo(), outra.nome(), true))).status());
    }
}
