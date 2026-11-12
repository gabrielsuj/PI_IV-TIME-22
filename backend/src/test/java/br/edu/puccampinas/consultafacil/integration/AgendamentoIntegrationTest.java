package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.AgendamentoRequest;
import br.edu.puccampinas.consultafacil.api.dto.ConsultaResponse;
import br.edu.puccampinas.consultafacil.api.dto.VagaDto;
import br.edu.puccampinas.consultafacil.api.dto.VagasResponse;
import br.edu.puccampinas.consultafacil.config.MongoConnection;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.repository.*;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import br.edu.puccampinas.consultafacil.service.AgendamentoService;
import br.edu.puccampinas.consultafacil.service.DisponibilidadeService;
import br.edu.puccampinas.consultafacil.service.HistoricoProcessor;
import br.edu.puccampinas.consultafacil.service.RegistroConsultaService;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Disponibilidade (#20) + busca de vagas e agendamento (#21) com o MongoDB de verdade.
 * Requer o MongoDB local com database/init aplicado no consultafacil_test
 * (scripts/recriar-banco.sh consultafacil_test).
 *
 * O relógio fica parado em 20/10/2026, antes da segunda-feira 26/10 do cenário de referência
 * (docs/modelagem/medicos-agenda.md §6). Horários em UTC: 08:00 em São Paulo = 11:00Z.
 */
class AgendamentoIntegrationTest {
    private static final ObjectId ESPECIALIDADE = new ObjectId("a00000000000000000000001");
    private static final ObjectId OUTRA_ESPECIALIDADE = new ObjectId("a00000000000000000000002");
    private static final ObjectId MEDICO = new ObjectId("a10000000000000000000001");          // com agenda
    private static final ObjectId MEDICO_SEM_AGENDA = new ObjectId("a10000000000000000000002");
    private static final ObjectId PACIENTE = new ObjectId("a40000000000000000000001");        // tem login
    private static final ObjectId OUTRO_PACIENTE = new ObjectId("a40000000000000000000002");  // sem login
    private static final String USUARIO_DO_PACIENTE = "a60000000000000000000001";

    private static final String SEGUNDA = "2026-10-26";
    private static final Instant AGORA = Instant.parse("2026-10-20T12:00:00Z");

    private static final UsuarioLogado SECRETARIA =
            new UsuarioLogado(new ObjectId().toHexString(), "Secretaria teste", Perfil.SECRETARIA_ADMIN);
    private static final UsuarioLogado PACIENTE_LOGADO =
            new UsuarioLogado(USUARIO_DO_PACIENTE, "Paciente teste", Perfil.PACIENTE);

    private static MongoConnection mongo;
    private AgendamentoService service;

    @BeforeAll
    static void conectar() {
        mongo = new MongoConnection("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000", "consultafacil_test");
    }

    @AfterAll
    static void fechar() { mongo.close(); }

    @BeforeEach
    void preparar() {
        MongoDatabase db = mongo.database();
        db.getCollection("consultas").deleteMany(new Document());
        db.getCollection("historico").deleteMany(new Document());
        db.getCollection("bloqueios").deleteMany(Filters.in("medicoId", MEDICO, MEDICO_SEM_AGENDA));
        db.getCollection("agendas").deleteMany(Filters.in("medicoId", MEDICO, MEDICO_SEM_AGENDA));

        salvar(db, "especialidades", new Document("_id", ESPECIALIDADE)
                .append("codigo", "teste-agendamento-1").append("nome", "Cardiologia Teste").append("ativa", true));
        salvar(db, "especialidades", new Document("_id", OUTRA_ESPECIALIDADE)
                .append("codigo", "teste-agendamento-2").append("nome", "Dermatologia Teste").append("ativa", true));
        salvar(db, "medicos", new Document("_id", MEDICO).append("nome", "Médica Teste")
                .append("crmNumero", "9900001").append("crmUf", "AC")
                .append("especialidadeIds", List.of(ESPECIALIDADE)).append("ativo", true));
        salvar(db, "medicos", new Document("_id", MEDICO_SEM_AGENDA).append("nome", "Médico Sem Agenda")
                .append("crmNumero", "9900002").append("crmUf", "AC")
                .append("especialidadeIds", List.of(ESPECIALIDADE)).append("ativo", true));
        salvar(db, "pacientes", new Document("_id", PACIENTE).append("nome", "Paciente Com Login")
                .append("cpf", "71428793860").append("email", "agendamento.teste1@consultafacil.test")
                .append("usuarioId", new ObjectId(USUARIO_DO_PACIENTE)));
        salvar(db, "pacientes", new Document("_id", OUTRO_PACIENTE).append("nome", "Paciente Sem Login")
                .append("cpf", "28625587887").append("email", "agendamento.teste2@consultafacil.test"));

        // Agenda igual à da Ana: segunda 08h–12h e 13h–17h, 30 min. Bloqueio na segunda 26/10 das 09h às 10h.
        db.getCollection("agendas").insertOne(new Document("medicoId", MEDICO)
                .append("fusoHorario", "America/Sao_Paulo").append("duracaoMinutos", 30)
                .append("jornadas", List.of(new Document("diaSemana", 1).append("faixas", List.of(
                        new Document("inicioMinuto", 480).append("fimMinuto", 720),
                        new Document("inicioMinuto", 780).append("fimMinuto", 1020))))));
        db.getCollection("bloqueios").insertOne(new Document("medicoId", MEDICO)
                .append("inicio", Date.from(Instant.parse("2026-10-26T12:00:00Z")))
                .append("fim", Date.from(Instant.parse("2026-10-26T13:00:00Z"))));

        Clock relogio = Clock.fixed(AGORA, ZoneOffset.UTC);
        var consultas = new ConsultaRepository(db);
        var registro = new RegistroConsultaService(consultas,
                new HistoricoProcessor(consultas, new HistoricoRepository.Mongo(db)), relogio);
        var disponibilidade = new DisponibilidadeService(new AgendaRepository(db), new BloqueioRepository(db),
                consultas, relogio);
        service = new AgendamentoService(disponibilidade, new MedicoRepository(db), new EspecialidadeRepository.Mongo(db),
                new PacienteRepository.Mongo(db), new PacienteDoUsuarioRepository(db), registro);
    }

    private static void salvar(MongoDatabase db, String colecao, Document d) {
        db.getCollection(colecao).replaceOne(Filters.eq("_id", d.get("_id")), d, new ReplaceOptions().upsert(true));
    }

    private static AgendamentoRequest pedido(ObjectId paciente, String inicio, String fim) {
        return new AgendamentoRequest(paciente == null ? null : paciente.toHexString(),
                MEDICO.toHexString(), ESPECIALIDADE.toHexString(), inicio, fim);
    }

    private List<String> iniciosDasVagas(String data) {
        VagasResponse r = service.buscarVagas(MEDICO.toHexString(), ESPECIALIDADE.toHexString(), data, data);
        List<String> inicios = new ArrayList<>();
        for (VagaDto v : r.horarios()) inicios.add(v.inicio());
        return inicios;
    }

    private static void assertStatus(int esperado, Runnable acao) {
        ApiException e = assertThrows(ApiException.class, acao::run);
        assertEquals(esperado, e.status(), e.getMessage());
    }

    // ------------------------------------------------------------------ busca de vagas

    @Test
    void deveBuscarVagasDescontandoBloqueio() {
        VagasResponse r = service.buscarVagas(MEDICO.toHexString(), ESPECIALIDADE.toHexString(), SEGUNDA, SEGUNDA);

        assertEquals("America/Sao_Paulo", r.fusoHorario());
        assertFalse(r.semAgenda());
        assertEquals(14, r.horarios().size());   // 16 da jornada − 2 no bloqueio
        assertEquals(List.of("2026-10-26T11:00:00Z", "2026-10-26T11:30:00Z", "2026-10-26T13:00:00Z"),
                iniciosDasVagas(SEGUNDA).subList(0, 3));   // 08:00, 08:30, 10:00
    }

    @Test
    void deveBuscarVagasEmIntervaloDeDatas() {
        // 26/10 a 02/11: duas segundas-feiras; a de 02/11 não tem bloqueio.
        VagasResponse r = service.buscarVagas(MEDICO.toHexString(), ESPECIALIDADE.toHexString(), SEGUNDA, "2026-11-02");
        assertEquals(14 + 16, r.horarios().size());
    }

    @Test
    void medicoSemAgendaRetornaListaVaziaComAviso() {
        VagasResponse r = service.buscarVagas(MEDICO_SEM_AGENDA.toHexString(), ESPECIALIDADE.toHexString(), SEGUNDA, SEGUNDA);
        assertTrue(r.semAgenda());
        assertTrue(r.horarios().isEmpty());
    }

    @Test
    void deveRecusarMedicoQueNaoAtendeAEspecialidade() {
        assertStatus(400, () -> service.buscarVagas(MEDICO.toHexString(), OUTRA_ESPECIALIDADE.toHexString(), SEGUNDA, SEGUNDA));
    }

    @Test
    void deveRecusarIntervaloMaiorQueTrintaEUmDias() {
        assertStatus(400, () -> service.buscarVagas(MEDICO.toHexString(), ESPECIALIDADE.toHexString(), "2026-11-01", "2026-12-15"));
    }

    // ------------------------------------------------------------------ agendamento

    @Test
    void secretariaAgendaParaOPacienteEHorarioSaiDaBusca() {
        ConsultaResponse c = service.agendar(
                pedido(OUTRO_PACIENTE, "2026-10-26T11:00:00Z", "2026-10-26T11:30:00Z"), SECRETARIA);

        assertEquals("AGENDADA", c.status());
        assertEquals(OUTRO_PACIENTE.toHexString(), c.pacienteId());
        assertFalse(iniciosDasVagas(SEGUNDA).contains("2026-10-26T11:00:00Z"));
        assertEquals(13, iniciosDasVagas(SEGUNDA).size());
    }

    /** Cenário do Quadro 4: reserva em período bloqueado é recusada. */
    @Test
    void deveImpedirReservaEmPeriodoBloqueado() {
        assertStatus(409, () -> service.agendar(
                pedido(OUTRO_PACIENTE, "2026-10-26T12:00:00Z", "2026-10-26T12:30:00Z"), SECRETARIA));   // 09:00
    }

    @Test
    void deveRecusarHorarioForaDaJornada() {
        // Terça-feira: o médico não atende.
        assertStatus(409, () -> service.agendar(
                pedido(OUTRO_PACIENTE, "2026-10-27T11:00:00Z", "2026-10-27T11:30:00Z"), SECRETARIA));
        // Segunda, mas fora da grade de 30 em 30 minutos (08:10).
        assertStatus(409, () -> service.agendar(
                pedido(OUTRO_PACIENTE, "2026-10-26T11:10:00Z", "2026-10-26T11:40:00Z"), SECRETARIA));
    }

    @Test
    void deveRecusarHorarioQueJaPassou() {
        // Segunda 19/10, antes do relógio do teste (20/10).
        assertStatus(409, () -> service.agendar(
                pedido(OUTRO_PACIENTE, "2026-10-19T11:00:00Z", "2026-10-19T11:30:00Z"), SECRETARIA));
    }

    @Test
    void horarioQueDeixouDeEstarLivreRetorna409() {
        service.agendar(pedido(OUTRO_PACIENTE, "2026-10-26T14:00:00Z", "2026-10-26T14:30:00Z"), SECRETARIA);

        // Outra pessoa tenta o mesmo horário com a lista antiga na tela.
        assertStatus(409, () -> service.agendar(
                pedido(PACIENTE, "2026-10-26T14:00:00Z", "2026-10-26T14:30:00Z"), SECRETARIA));
    }

    @Test
    void secretariaPrecisaInformarOPaciente() {
        assertStatus(400, () -> service.agendar(pedido(null, "2026-10-26T11:00:00Z", "2026-10-26T11:30:00Z"), SECRETARIA));
    }

    @Test
    void pacienteAgendaParaSiMesmoSemInformarPacienteId() {
        ConsultaResponse c = service.agendar(
                pedido(null, "2026-10-26T11:00:00Z", "2026-10-26T11:30:00Z"), PACIENTE_LOGADO);
        assertEquals(PACIENTE.toHexString(), c.pacienteId());
    }

    @Test
    void pacienteNaoAgendaParaOutroPaciente() {
        assertStatus(403, () -> service.agendar(
                pedido(OUTRO_PACIENTE, "2026-10-26T11:00:00Z", "2026-10-26T11:30:00Z"), PACIENTE_LOGADO));
    }

    @Test
    void loginDePacienteSemCadastroLigadoRecebe403() {
        var semCadastro = new UsuarioLogado(new ObjectId().toHexString(), "Sem cadastro", Perfil.PACIENTE);
        assertStatus(403, () -> service.agendar(
                pedido(null, "2026-10-26T11:00:00Z", "2026-10-26T11:30:00Z"), semCadastro));
    }
}
