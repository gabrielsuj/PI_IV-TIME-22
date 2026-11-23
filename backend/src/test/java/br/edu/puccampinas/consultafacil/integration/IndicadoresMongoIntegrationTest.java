/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.config.MongoConnection;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.repository.IndicadoresMongoRepository;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import br.edu.puccampinas.consultafacil.service.HistoricoProcessor;
import br.edu.puccampinas.consultafacil.service.RegistroConsultaService;
import br.edu.puccampinas.consultafacil.support.ConsultasEmMemoria;
import br.edu.puccampinas.consultafacil.support.FontesIndicadoresEmMemoria;
import br.edu.puccampinas.consultafacil.support.RelogioAjustavel;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Sorts.ascending;
import static org.junit.jupiter.api.Assertions.*;

// A fixture auditada permite conferir os totais no MongoDB real e verificar que a
// leitura dos indicadores não produz escritas.
class IndicadoresMongoIntegrationTest {
    private static MongoConnection mongo;
    private final ObjectId medico = new ObjectId(), especialidade = new ObjectId();
    private final List<ObjectId> idsConsultas = new ArrayList<>();
    private MongoDatabase db;

    @BeforeAll static void conectar() {
        String uri = System.getenv("HISTORICO_MONGO_URI");
        Assumptions.assumeTrue(uri != null && !uri.isBlank(), "Defina HISTORICO_MONGO_URI com o banco de testes e seus schemas já aplicados");
        mongo = new MongoConnection(uri, "consultafacil_historico_test");
        mongo.ping();
        for (String nome : List.of("medicos", "consultas", "agendas", "bloqueios", "historico", "filaEspera")) {
            Document colecao = mongo.database().listCollections().filter(eq("name", nome)).first();
            assertNotNull(colecao, "Coleção de teste não inicializada: " + nome);
            assertTrue(colecao.get("options", Document.class).containsKey("validator"), "Schema de teste ausente: " + nome);
        }
    }
    @AfterAll static void fechar() { if (mongo != null) mongo.close(); }

    @BeforeEach void prepararFixtureAuditada() {
        db = mongo.database();
        var fixture = FontesIndicadoresEmMemoria.fixture();
        var memoria = new ConsultasEmMemoria();
        List<Document> eventos = new ArrayList<>();
        var clock = new RelogioAjustavel(Instant.parse("2026-11-20T15:00:00Z"));
        var registro = new RegistroConsultaService(memoria, new HistoricoProcessor(memoria, eventos::add), clock);
        var ator = new UsuarioLogado(new ObjectId().toHexString(), "Secretaria Teste", Perfil.SECRETARIA_ADMIN);
        for (Document modelo : fixture.consultas) {
            ObjectId id = registro.criar(new Document("pacienteId", new ObjectId()).append("medicoId", medico)
                    .append("especialidadeId", especialidade).append("inicio", modelo.getDate("inicio"))
                    .append("fim", modelo.getDate("fim")), ator).getObjectId("_id");
            idsConsultas.add(id);
            if (Set.of("CONFIRMADA", "CONCLUIDA", "CANCELADA").contains(modelo.getString("status")))
                registro.alterar(id, new Document("status", "CONFIRMADA"), ator);
            if ("CANCELADA".equals(modelo.getString("status"))) registro.alterar(id, new Document("status", "CANCELADA"), ator);
        }
        clock.avancar(Duration.ofDays(4));
        for (int i = 0; i < fixture.consultas.size(); i++) {
            String status = fixture.consultas.get(i).getString("status");
            if (Set.of("CONCLUIDA", "FALTA").contains(status)) registro.alterar(idsConsultas.get(i), new Document("status", status), ator);
        }
        db.getCollection("medicos").insertOne(new Document("_id", medico).append("nome", "Médico Indicadores Teste")
                .append("crmNumero", Long.toString(Long.parseLong(medico.toHexString().substring(16), 16)))
                .append("crmUf", "RR").append("especialidadeIds", List.of(especialidade)).append("ativo", false));
        db.getCollection("agendas").insertOne(new Document(fixture.agendas.getFirst()).append("medicoId", medico));
        db.getCollection("bloqueios").insertOne(new Document(fixture.bloqueios.get(FontesIndicadoresEmMemoria.MEDICO).getFirst()).append("medicoId", medico));
        db.getCollection("consultas").insertMany(new ArrayList<>(memoria.docs.values()));
        db.getCollection("historico").insertMany(eventos);
        Date criada = Date.from(Instant.parse("2026-11-20T15:00:00Z"));
        for (int indice : List.of(0, 0, 2, 5)) {
            ObjectId id = idsConsultas.get(indice);
            db.getCollection("filaEspera").insertOne(new Document("_id", new ObjectId())
                    .append("pacienteId", memoria.docs.get(id).getObjectId("pacienteId")).append("especialidadeId", especialidade)
                    .append("medicoId", null).append("turnoPreferido", "QUALQUER").append("status", "ATENDIDA")
                    .append("ultimaSinalizacao", null).append("consultaAgendadaId", id).append("atendidaEm", criada)
                    .append("validoAte", Date.from(criada.toInstant().plus(Duration.ofDays(90))))
                    .append("registradoPor", new Document("perfil", "SECRETARIA").append("id", new ObjectId(ator.id())))
                    .append("criadoEm", criada).append("atualizadoEm", criada));
        }
    }

    @AfterEach void removerApenasFixtureCriada() {
        if (db == null) return;
        if (!idsConsultas.isEmpty()) db.getCollection("filaEspera").deleteMany(in("consultaAgendadaId", idsConsultas));
        for (String colecao : List.of("consultas", "historico", "agendas", "bloqueios"))
            db.getCollection(colecao).deleteMany(eq("medicoId", medico));
        db.getCollection("medicos").deleteOne(eq("_id", medico));
    }

    @Test void mongoRealConsolidaFixtureManualComSchemasSemEscreverDuranteALeitura() {
        var antes = snapshot();
        var r = new IndicadoresMongoRepository(db).ler(FontesIndicadoresEmMemoria.PERIODO, Optional.of(medico));
        assertEquals(1, r.consultas().agendadas());
        assertEquals(1, r.consultas().confirmadas());
        assertEquals(2, r.consultas().concluidas());
        assertEquals(1, r.consultas().faltas());
        assertEquals(1, r.consultas().canceladas());
        assertEquals(8, r.capacidade().total());
        assertEquals(6, r.capacidade().ocupadas());
        assertEquals(4, r.confirmacao().registradas());
        assertEquals(0, r.confirmacao().comProjecaoPendente());
        assertEquals(0, r.confirmacao().semBaseHistorica());
        assertEquals(2, r.fila().preenchidas());
        assertEquals(0, r.fila().inscricoesComVinculoInvalido());
        assertEquals(antes, snapshot());
    }

    @Test void medicoNaoCadastradoRecebe404SemConfundirComPeriodoVazio() {
        assertEquals(404, assertThrows(ApiException.class, () -> new IndicadoresMongoRepository(db)
                .ler(FontesIndicadoresEmMemoria.PERIODO, Optional.of(new ObjectId()))).status());
    }

    private Map<String, List<Document>> snapshot() {
        Map<String, List<Document>> estado = new HashMap<>();
        for (String colecao : List.of("medicos", "consultas", "historico", "agendas", "bloqueios", "filaEspera")) {
            var filtro = colecao.equals("medicos") ? eq("_id", medico)
                    : colecao.equals("filaEspera") ? in("consultaAgendadaId", idsConsultas) : eq("medicoId", medico);
            estado.put(colecao, db.getCollection(colecao).find(filtro).sort(ascending("_id")).into(new ArrayList<>()));
        }
        return estado;
    }
}
