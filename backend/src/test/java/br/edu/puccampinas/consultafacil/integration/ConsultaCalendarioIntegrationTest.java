package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.ConsultaCalendarioResponse;
import br.edu.puccampinas.consultafacil.config.MongoConnection;
import br.edu.puccampinas.consultafacil.repository.AgendaRepository;
import br.edu.puccampinas.consultafacil.repository.ConsultaRepository;
import br.edu.puccampinas.consultafacil.repository.MedicoRepository;
import br.edu.puccampinas.consultafacil.repository.NomesRepository;
import br.edu.puccampinas.consultafacil.service.ConsultaCalendarioService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Calendário operacional (#23). Requer o MongoDB local; usa e limpa o banco consultafacil_test. */
class ConsultaCalendarioIntegrationTest {
    private static final ObjectId ANA = new ObjectId("100000000000000000000001");
    private static final ObjectId BRUNO = new ObjectId("100000000000000000000002");
    private static final ObjectId CLINICA = new ObjectId("000000000000000000000001");
    private static final ObjectId PAULO = new ObjectId("400000000000000000000001");
    private static final ObjectId CARLA = new ObjectId("400000000000000000000002");

    private static MongoConnection mongo;
    private ConsultaCalendarioService service;

    @BeforeAll static void conectar() {
        mongo = new MongoConnection("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000", "consultafacil_test");
    }

    @AfterAll static void fechar() { mongo.close(); }

    @BeforeEach void preparar() {
        var db = mongo.database();
        for (String c : List.of("consultas", "agendas", "medicos", "pacientes", "especialidades"))
            db.getCollection(c).deleteMany(new Document());
        db.getCollection("especialidades").insertOne(new Document("_id", CLINICA)
                .append("codigo", "clinica-geral").append("nome", "Clínica Geral").append("ativa", true));
        for (ObjectId medico : List.of(ANA, BRUNO))
            db.getCollection("medicos").insertOne(new Document("_id", medico).append("nome", "Médico " + medico)
                    .append("crmNumero", medico.equals(ANA) ? "000123" : "004567").append("crmUf", "SP")
                    .append("especialidadeIds", List.of(CLINICA)).append("ativo", true));
        db.getCollection("pacientes").insertMany(List.of(
                new Document("_id", PAULO).append("nome", "Paulo Paciente").append("cpf", "39053344705").append("email", "paulo@example.com"),
                new Document("_id", CARLA).append("nome", "Carla Souza").append("cpf", "12345678909").append("email", "carla@example.com")));

        // 10/11 (segunda) e 12/11 (quarta), horários de São Paulo (UTC-3)
        consulta(ANA, PAULO, "2026-11-10T12:00:00Z", "CONCLUIDA");   // 09:00 local
        consulta(ANA, CARLA, "2026-11-10T13:00:00Z", "CANCELADA");   // 10:00 local
        consulta(ANA, CARLA, "2026-11-12T11:00:00Z", "AGENDADA");    // 08:00 local
        consulta(ANA, PAULO, "2026-11-17T11:00:00Z", "CONFIRMADA");  // fora do período (semana seguinte)
        consulta(BRUNO, PAULO, "2026-11-11T17:00:00Z", "AGENDADA");  // outro médico

        service = new ConsultaCalendarioService(new ConsultaRepository(db), new AgendaRepository(db),
                new MedicoRepository(db), new NomesRepository.Mongo(db));
    }

    private static void consulta(ObjectId medico, ObjectId paciente, String inicioUtc, String status) {
        Instant inicio = Instant.parse(inicioUtc);
        mongo.database().getCollection("consultas").insertOne(new Document("medicoId", medico)
                .append("pacienteId", paciente).append("especialidadeId", CLINICA)
                .append("inicio", Date.from(inicio)).append("fim", Date.from(inicio.plusSeconds(1800)))
                .append("status", status).append("criadaEm", new Date()));
    }

    @Test void listaConsultasDoMedicoNoPeriodoComNomesEHorarioLocal() {
        List<ConsultaCalendarioResponse> semana = service.listar(ANA, "2026-11-09", "2026-11-15");

        assertEquals(3, semana.size(), "todas as da Ana na semana, inclusive a cancelada");
        assertEquals(List.of("2026-11-10T09:00", "2026-11-10T10:00", "2026-11-12T08:00"),
                semana.stream().map(ConsultaCalendarioResponse::inicio).toList());
        assertEquals("2026-11-10T09:30", semana.get(0).fim());
        assertEquals("Paulo Paciente", semana.get(0).pacienteNome());
        assertEquals("Clínica Geral", semana.get(0).especialidadeNome());
        assertEquals(List.of("CONCLUIDA", "CANCELADA", "AGENDADA"),
                semana.stream().map(ConsultaCalendarioResponse::status).toList());
    }

    @Test void periodoDeUmDiaUsaOFusoDaAgenda() {
        // 12/11 local começa às 03:00 UTC: a consulta das 11:00 UTC (08:00 local) entra, a do dia 10 não.
        List<ConsultaCalendarioResponse> dia = service.listar(ANA, "2026-11-12", "2026-11-12");
        assertEquals(1, dia.size());
        assertEquals("Carla Souza", dia.get(0).pacienteNome());
    }

    @Test void periodoSemConsultasRetornaListaVazia() {
        assertTrue(service.listar(ANA, "2026-12-01", "2026-12-07").isEmpty());
    }

    @Test void recusaPeriodoInvalido() {
        assertEquals(400, assertThrows(ApiException.class, () -> service.listar(ANA, "2026-11-15", "2026-11-09")).status());
        assertEquals(400, assertThrows(ApiException.class, () -> service.listar(ANA, "2026-11-01", "2027-01-31")).status());
        assertEquals(400, assertThrows(ApiException.class, () -> service.listar(ANA, "10/11/2026", "2026-11-15")).status());
        assertEquals(400, assertThrows(ApiException.class, () -> service.listar(ANA, null, "2026-11-15")).status());
    }

    @Test void medicoInexistenteRetorna404() {
        var e = assertThrows(ApiException.class,
                () -> service.listar(new ObjectId("100000000000000000000099"), "2026-11-09", "2026-11-15"));
        assertEquals(404, e.status());
    }
}
