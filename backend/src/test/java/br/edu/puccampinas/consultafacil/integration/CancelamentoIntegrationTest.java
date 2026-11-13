package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.AgendamentoRequest;
import br.edu.puccampinas.consultafacil.api.dto.ConsultaResponse;
import br.edu.puccampinas.consultafacil.api.dto.VagaDto;
import br.edu.puccampinas.consultafacil.config.MongoConnection;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.repository.*;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import br.edu.puccampinas.consultafacil.service.*;
import br.edu.puccampinas.consultafacil.support.RelogioAjustavel;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cancelamento (#27) com o MongoDB de verdade: o horário volta para GET /vagas, aceita nova reserva
 * e o histórico recebe o evento CANCELAMENTO.
 * Requer o MongoDB local com database/init aplicado no consultafacil_test
 * (scripts/recriar-banco.sh consultafacil_test).
 *
 * Relógio em 20/10/2026; a consulta é na segunda 26/10 às 08:00 de São Paulo (11:00Z).
 */
class CancelamentoIntegrationTest {
    private static final ObjectId ESPECIALIDADE = new ObjectId("c00000000000000000000001");
    private static final ObjectId MEDICO = new ObjectId("c10000000000000000000001");
    private static final ObjectId PACIENTE = new ObjectId("c40000000000000000000001");
    private static final String USUARIO_DO_PACIENTE = "c60000000000000000000001";
    private static final String USUARIO_DO_MEDICO = "c70000000000000000000001";

    private static final String SEGUNDA = "2026-10-26";
    private static final String INICIO = "2026-10-26T11:00:00Z";
    private static final String FIM = "2026-10-26T11:30:00Z";

    private static final UsuarioLogado PACIENTE_LOGADO = new UsuarioLogado(USUARIO_DO_PACIENTE, "Paciente", Perfil.PACIENTE);
    private static final UsuarioLogado MEDICO_LOGADO = new UsuarioLogado(USUARIO_DO_MEDICO, "Médico", Perfil.MEDICO);

    private static MongoConnection mongo;
    private MongoDatabase db;
    private RelogioAjustavel relogio;
    private AgendamentoService agendamento;
    private CancelamentoService cancelamento;
    private final List<Document> avisosFila = new ArrayList<>();

    @BeforeAll
    static void conectar() {
        mongo = new MongoConnection("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000", "consultafacil_test");
    }

    @AfterAll
    static void fechar() { mongo.close(); }

    @BeforeEach
    void preparar() {
        db = mongo.database();
        db.getCollection("consultas").deleteMany(new Document());
        db.getCollection("historico").deleteMany(new Document());
        db.getCollection("bloqueios").deleteMany(Filters.eq("medicoId", MEDICO));
        db.getCollection("agendas").deleteMany(Filters.eq("medicoId", MEDICO));

        salvar("especialidades", new Document("_id", ESPECIALIDADE)
                .append("codigo", "teste-cancelamento-1").append("nome", "Clínica Teste Cancelamento").append("ativa", true));
        salvar("medicos", new Document("_id", MEDICO).append("nome", "Médica Teste Cancelamento")
                .append("crmNumero", "9900301").append("crmUf", "AC")
                .append("especialidadeIds", List.of(ESPECIALIDADE)).append("ativo", true)
                .append("usuarioId", new ObjectId(USUARIO_DO_MEDICO)));
        salvar("pacientes", new Document("_id", PACIENTE).append("nome", "Paciente Teste Cancelamento")
                .append("cpf", "84736291591").append("email", "cancelamento.teste1@consultafacil.test")
                .append("usuarioId", new ObjectId(USUARIO_DO_PACIENTE)));
        db.getCollection("agendas").insertOne(new Document("medicoId", MEDICO)
                .append("fusoHorario", "America/Sao_Paulo").append("duracaoMinutos", 30)
                .append("jornadas", List.of(new Document("diaSemana", 1).append("faixas", List.of(
                        new Document("inicioMinuto", 480).append("fimMinuto", 720))))));

        relogio = new RelogioAjustavel(Instant.parse("2026-10-20T12:00:00Z"));
        var consultas = new ConsultaRepository(db);
        var registro = new RegistroConsultaService(consultas,
                new HistoricoProcessor(consultas, new HistoricoRepository.Mongo(db)), relogio);
        var pacienteDoUsuario = new PacienteDoUsuarioRepository(db);
        agendamento = new AgendamentoService(
                new DisponibilidadeService(new AgendaRepository(db), new BloqueioRepository(db), consultas, relogio),
                new MedicoRepository(db), new EspecialidadeRepository.Mongo(db), new PacienteRepository.Mongo(db),
                pacienteDoUsuario, registro);
        cancelamento = new CancelamentoService(consultas, pacienteDoUsuario::buscar,
                new MedicoDoUsuarioRepository(db)::buscar, registro, avisosFila::add, relogio);
    }

    private void salvar(String colecao, Document d) {
        db.getCollection(colecao).replaceOne(Filters.eq("_id", d.get("_id")), d, new ReplaceOptions().upsert(true));
    }

    private ObjectId agendarAs8h() {
        ConsultaResponse r = agendamento.agendar(new AgendamentoRequest(null, MEDICO.toHexString(),
                ESPECIALIDADE.toHexString(), INICIO, FIM), PACIENTE_LOGADO);
        return new ObjectId(r.id());
    }

    private List<String> iniciosDasVagas() {
        return agendamento.buscarVagas(MEDICO.toHexString(), ESPECIALIDADE.toHexString(), SEGUNDA, SEGUNDA)
                .horarios().stream().map(VagaDto::inicio).toList();
    }

    @Test
    void deveLiberarAVagaParaABuscaEParaNovaReserva() {
        ObjectId id = agendarAs8h();
        assertFalse(iniciosDasVagas().contains(INICIO));

        cancelamento.cancelar(id, null, PACIENTE_LOGADO);

        assertTrue(iniciosDasVagas().contains(INICIO), "o horário cancelado volta para GET /vagas");
        // O índice único é parcial (só ativas): a mesma vaga aceita outra consulta.
        assertDoesNotThrow(this::agendarAs8h);
    }

    @Test
    void deveRegistrarCancelamentoNoHistoricoComMotivoEAtor() {
        ObjectId id = agendarAs8h();

        cancelamento.cancelar(id, "Conflito com outro compromisso", MEDICO_LOGADO);

        List<Document> eventos = db.getCollection("historico").find(Filters.eq("consultaId", id))
                .sort(Sorts.ascending("sequencia")).into(new ArrayList<>());
        assertEquals(List.of("CRIACAO", "CANCELAMENTO"), eventos.stream().map(e -> e.getString("evento")).toList());
        Document cancel = eventos.get(1);
        assertEquals("AGENDADA", cancel.getString("statusAnterior"));
        assertEquals("CANCELADA", cancel.getString("statusNovo"));
        assertEquals("Conflito com outro compromisso", cancel.getString("motivo"));
        assertEquals("MEDICO", cancel.get("ator", Document.class).getString("perfil"));
        assertEquals(new ObjectId(USUARIO_DO_MEDICO), cancel.get("ator", Document.class).getObjectId("id"));

        Document consulta = db.getCollection("consultas").find(Filters.eq("_id", id)).first();
        assertEquals("CANCELADA", consulta.getString("status"));
        assertEquals("Conflito com outro compromisso", consulta.getString("motivoCancelamento"));
        assertTrue(consulta.getList("eventosPendentes", Document.class).isEmpty());
    }

    @Test
    void deveAvisarAFilaComAConsultaCancelada() {
        ObjectId id = agendarAs8h();
        cancelamento.cancelar(id, null, PACIENTE_LOGADO);

        assertEquals(1, avisosFila.size());
        assertEquals(id, avisosFila.get(0).getObjectId("_id"));
        assertEquals(MEDICO, avisosFila.get(0).getObjectId("medicoId"));
        assertEquals(Date.from(Instant.parse(INICIO)), avisosFila.get(0).getDate("inicio"));
    }

    @Test
    void naoCancelaConsultaQueJaComecou() {
        ObjectId id = agendarAs8h();
        relogio.avancar(Duration.ofDays(6).minusHours(1));   // 26/10 11:00Z, o horário de início

        ApiException e = assertThrows(ApiException.class, () -> cancelamento.cancelar(id, null, PACIENTE_LOGADO));
        assertEquals(409, e.status());
        assertEquals(1, db.getCollection("historico").countDocuments(Filters.eq("consultaId", id)));
    }
}
