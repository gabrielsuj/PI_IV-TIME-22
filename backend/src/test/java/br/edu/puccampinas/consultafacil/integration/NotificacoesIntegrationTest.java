/*
Autoria: Murilo Moraes
RA: 25000073
*/
package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.dto.AgendamentoRequest;
import br.edu.puccampinas.consultafacil.config.AppConfig;
import br.edu.puccampinas.consultafacil.config.MongoConnection;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.repository.*;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import br.edu.puccampinas.consultafacil.service.*;
import br.edu.puccampinas.consultafacil.support.RelogioAjustavel;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
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
 * Lembretes e notificações simuladas (#34) com o MongoDB de verdade, incluindo o validador da coleção
 * notificacoes (database/init/09-esquema-notificacoes.js) e o índice único que impede duplicados.
 * Requer o MongoDB local com database/init aplicado no consultafacil_test
 * (scripts/recriar-banco.sh consultafacil_test).
 *
 * Relógio em 20/10/2026 12:00Z; a consulta é na segunda 26/10 às 08:00 de São Paulo (11:00Z).
 * Com antecedência de 24 h, o lembrete fica programado para 25/10 às 11:00Z.
 */
class NotificacoesIntegrationTest {
    private static final ObjectId ESPECIALIDADE = new ObjectId("e50000000000000000000001");
    private static final ObjectId MEDICO = new ObjectId("e51000000000000000000001");
    private static final ObjectId PACIENTE = new ObjectId("e54000000000000000000001");
    private static final String USUARIO_DO_PACIENTE = "e56000000000000000000001";
    private static final String USUARIO_DO_MEDICO = "e57000000000000000000001";
    private static final String CPF = "52998224725";

    private static final String INICIO = "2026-10-26T11:00:00Z";
    private static final String FIM = "2026-10-26T11:30:00Z";
    private static final Instant LEMBRETE = Instant.parse("2026-10-25T11:00:00Z");

    private static final UsuarioLogado PACIENTE_LOGADO = new UsuarioLogado(USUARIO_DO_PACIENTE, "Paciente", Perfil.PACIENTE);
    private static final UsuarioLogado MEDICO_LOGADO = new UsuarioLogado(USUARIO_DO_MEDICO, "Médico", Perfil.MEDICO);
    private static final UsuarioLogado SECRETARIA = new UsuarioLogado("e58000000000000000000001", "Secretaria", Perfil.SECRETARIA_ADMIN);

    private static MongoConnection mongo;
    private MongoDatabase db;
    private RelogioAjustavel relogio;
    private ConsultaRepository consultas;
    private RegistroConsultaService registroSemNotificacao;
    private final List<Document> emailsEnviados = new ArrayList<>();

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
        db.getCollection("notificacoes").deleteMany(Filters.in("destinatarioId", List.of(PACIENTE, MEDICO)));
        db.getCollection("bloqueios").deleteMany(Filters.eq("medicoId", MEDICO));
        db.getCollection("agendas").deleteMany(Filters.eq("medicoId", MEDICO));

        salvar("especialidades", new Document("_id", ESPECIALIDADE)
                .append("codigo", "teste-notificacoes-1").append("nome", "Clínica Teste Notificações").append("ativa", true));
        salvar("medicos", new Document("_id", MEDICO).append("nome", "Dra. Teste Notificações")
                .append("crmNumero", "9900341").append("crmUf", "AC")
                .append("especialidadeIds", List.of(ESPECIALIDADE)).append("ativo", true)
                .append("usuarioId", new ObjectId(USUARIO_DO_MEDICO)));
        salvar("pacientes", new Document("_id", PACIENTE).append("nome", "Paciente Teste Notificações")
                .append("cpf", CPF).append("email", "notificacoes.teste1@consultafacil.test")
                .append("usuarioId", new ObjectId(USUARIO_DO_PACIENTE)));
        db.getCollection("agendas").insertOne(new Document("medicoId", MEDICO)
                .append("fusoHorario", "America/Sao_Paulo").append("duracaoMinutos", 30)
                .append("jornadas", List.of(new Document("diaSemana", 1).append("faixas", List.of(
                        new Document("inicioMinuto", 480).append("fimMinuto", 720))))));

        relogio = new RelogioAjustavel(Instant.parse("2026-10-20T12:00:00Z"));
        consultas = new ConsultaRepository(db);
        registroSemNotificacao = new RegistroConsultaService(consultas,
                new HistoricoProcessor(consultas, new HistoricoRepository.Mongo(db)), relogio);
    }

    private void salvar(String colecao, Document d) {
        db.getCollection(colecao).replaceOne(Filters.eq("_id", d.get("_id")), d, new ReplaceOptions().upsert(true));
    }

    /** Monta o mesmo encadeamento do App: agendamento e cancelamento avisam o NotificacoesService. */
    private Sistema sistema(boolean emailTeste, EntregaEmailTeste email) {
        var notificacoes = new NotificacoesService(new NotificacaoRepository(db), consultas, new NomesRepository.Mongo(db),
                email, new AppConfig.Notificacoes(24, 60, emailTeste), relogio);
        var registro = new RegistroConsultaService(consultas,
                new HistoricoProcessor(consultas, new HistoricoRepository.Mongo(db)), relogio,
                id -> {}, notificacoes::consultaAgendada);
        return new Sistema(notificacoes, agendamento(registro), new CancelamentoService(consultas,
                new AcessoConsulta(new PacienteDoUsuarioRepository(db)::buscar, new MedicoDoUsuarioRepository(db)::buscar),
                registro, notificacoes::consultaCancelada, relogio));
    }

    private Sistema sistema() { return sistema(false, emailsEnviados::add); }

    private record Sistema(NotificacoesService notificacoes, AgendamentoService agendamento, CancelamentoService cancelamento) {}

    private AgendamentoService agendamento(RegistroConsultaService registro) {
        return new AgendamentoService(
                new DisponibilidadeService(new AgendaRepository(db), new BloqueioRepository(db), consultas, relogio),
                new MedicoRepository(db), new EspecialidadeRepository.Mongo(db), new PacienteRepository.Mongo(db),
                new PacienteDoUsuarioRepository(db), registro);
    }

    private ObjectId agendarAs8h(AgendamentoService agendamento) {
        return new ObjectId(agendamento.agendar(new AgendamentoRequest(null, MEDICO.toHexString(),
                ESPECIALIDADE.toHexString(), INICIO, FIM), PACIENTE_LOGADO).id());
    }

    private List<Document> notificacoes(Bson... filtros) {
        var todos = new ArrayList<Bson>(List.of(Filters.in("destinatarioId", List.of(PACIENTE, MEDICO))));
        todos.addAll(List.of(filtros));
        return db.getCollection("notificacoes").find(Filters.and(todos)).into(new ArrayList<>());
    }

    private Document unica(String tipo, Perfil perfil) {
        var lista = notificacoes(Filters.eq("tipo", tipo), Filters.eq("destinatarioPerfil", perfil.name()),
                Filters.eq("canal", "SISTEMA"));
        assertEquals(1, lista.size(), tipo + " para " + perfil);
        return lista.get(0);
    }

    // ------------------------------------------------------------------ agendamento

    @Test
    void agendarEntregaAConfirmacaoNaHoraEProgramaOLembrete24hAntes() {
        Sistema s = sistema();
        ObjectId id = agendarAs8h(s.agendamento());

        Document confirmacao = unica("CONFIRMACAO_AGENDAMENTO", Perfil.PACIENTE);
        assertEquals("ENVIADA", confirmacao.getString("status"), "aparece na central sem esperar o agendador");
        assertEquals(id, confirmacao.getObjectId("consultaId"));
        assertEquals(1, confirmacao.getInteger("tentativas"));
        assertTrue(confirmacao.getString("mensagem").contains("26/10 às 08:00"), "horário de São Paulo");
        assertTrue(confirmacao.getString("mensagem").contains("Dra. Teste Notificações"));

        Document lembrete = unica("LEMBRETE_CONSULTA", Perfil.PACIENTE);
        assertEquals("AGENDADA", lembrete.getString("status"));
        assertEquals(24, lembrete.getInteger("antecedenciaHoras"));
        assertEquals(Date.from(LEMBRETE), lembrete.getDate("agendadaPara"));
        assertEquals(Date.from(Instant.parse(INICIO)), lembrete.get("dadosConsulta", Document.class).getDate("dataHora"));
    }

    @Test
    void oLembreteSoEEnviadoQuandoChegaAHoraEUmaVezSo() {
        Sistema s = sistema();
        agendarAs8h(s.agendamento());

        relogio.avancar(Duration.between(relogio.instant(), LEMBRETE).minusMinutes(1));
        s.notificacoes().executarCiclo();
        assertEquals("AGENDADA", unica("LEMBRETE_CONSULTA", Perfil.PACIENTE).getString("status"), "um minuto antes: ainda não");

        relogio.avancar(Duration.ofMinutes(1));
        s.notificacoes().executarCiclo();
        s.notificacoes().executarCiclo();
        Document lembrete = unica("LEMBRETE_CONSULTA", Perfil.PACIENTE);
        assertEquals("ENVIADA", lembrete.getString("status"));
        assertEquals(Date.from(LEMBRETE), lembrete.getDate("enviadaEm"));
        assertEquals(1, lembrete.getInteger("tentativas"), "o segundo ciclo não reenvia");
    }

    @Test
    void consultaSemLembreteNaJanelaGanhaUmSoPeloAgendador() {
        // Consulta gravada sem passar pelas notificações (ex.: carga de demonstração).
        ObjectId id = agendarAs8h(agendamento(registroSemNotificacao));
        Sistema s = sistema();

        s.notificacoes().executarCiclo();
        assertTrue(notificacoes(Filters.eq("consultaId", id)).isEmpty(), "fora da janela de 24 h ainda");

        relogio.avancar(Duration.between(relogio.instant(), LEMBRETE).plusHours(2)); // 22 h antes da consulta
        s.notificacoes().executarCiclo();
        s.notificacoes().executarCiclo();

        Document lembrete = unica("LEMBRETE_CONSULTA", Perfil.PACIENTE);
        assertEquals("ENVIADA", lembrete.getString("status"));
        assertEquals(Date.from(relogio.instant()), lembrete.getDate("agendadaPara"), "a hora programada já passou: vai agora");
    }

    @Test
    void consultaMarcadaEmCimaDaHoraRecebeOLembreteJa() {
        relogio.avancar(Duration.between(relogio.instant(), Instant.parse(INICIO)).minusHours(3));
        Sistema s = sistema();
        agendarAs8h(s.agendamento());

        Document lembrete = unica("LEMBRETE_CONSULTA", Perfil.PACIENTE);
        assertEquals("ENVIADA", lembrete.getString("status"));
    }

    // ------------------------------------------------------------------ cancelamento

    @Test
    void cancelamentoPelaSecretariaAvisaPacienteEMedicoEDescartaOLembrete() {
        Sistema s = sistema();
        ObjectId id = agendarAs8h(s.agendamento());

        s.cancelamento().cancelar(id, "Paciente pediu por telefone", SECRETARIA);

        assertEquals("CANCELADA", unica("LEMBRETE_CONSULTA", Perfil.PACIENTE).getString("status"));
        Document paraPaciente = unica("CANCELAMENTO_CONSULTA", Perfil.PACIENTE);
        Document paraMedico = unica("CANCELAMENTO_CONSULTA", Perfil.MEDICO);
        assertEquals("ENVIADA", paraPaciente.getString("status"));
        assertEquals("ENVIADA", paraMedico.getString("status"));
        assertEquals(MEDICO, paraMedico.getObjectId("destinatarioId"));
        assertEquals("CANCELADA", paraMedico.getString("statusConsulta"));
        for (Document n : List.of(paraPaciente, paraMedico))
            assertFalse(n.getString("mensagem").contains("telefone"), "o motivo do cancelamento não vai na mensagem");

        relogio.avancar(Duration.between(relogio.instant(), LEMBRETE));
        s.notificacoes().executarCiclo();
        assertEquals("CANCELADA", unica("LEMBRETE_CONSULTA", Perfil.PACIENTE).getString("status"),
                "o agendador não recria o lembrete de consulta cancelada");
    }

    @Test
    void quemCancelaNaoRecebeAvisoDoProprioCancelamento() {
        Sistema s = sistema();
        ObjectId doPaciente = agendarAs8h(s.agendamento());
        s.cancelamento().cancelar(doPaciente, null, PACIENTE_LOGADO);
        assertEquals(List.of("MEDICO"), notificacoes(Filters.eq("consultaId", doPaciente),
                Filters.eq("tipo", "CANCELAMENTO_CONSULTA")).stream().map(n -> n.getString("destinatarioPerfil")).toList());

        ObjectId doMedico = agendarAs8h(s.agendamento());
        s.cancelamento().cancelar(doMedico, null, MEDICO_LOGADO);
        assertEquals(List.of("PACIENTE"), notificacoes(Filters.eq("consultaId", doMedico),
                Filters.eq("tipo", "CANCELAMENTO_CONSULTA")).stream().map(n -> n.getString("destinatarioPerfil")).toList());
    }

    @Test
    void lembreteDeConsultaQueMudouDeHorarioEDescartado() {
        Sistema s = sistema();
        ObjectId id = agendarAs8h(s.agendamento());
        db.getCollection("consultas").updateOne(Filters.eq("_id", id), Updates.combine(
                Updates.set("inicio", Date.from(Instant.parse("2026-10-26T12:00:00Z"))),
                Updates.set("fim", Date.from(Instant.parse("2026-10-26T12:30:00Z")))));

        relogio.avancar(Duration.between(relogio.instant(), LEMBRETE));
        s.notificacoes().executarCiclo();

        assertEquals("CANCELADA", notificacoes(Filters.eq("tipo", "LEMBRETE_CONSULTA"),
                Filters.eq("antecedenciaHoras", 24), Filters.eq("dadosConsulta.dataHora", Date.from(Instant.parse(INICIO))))
                .get(0).getString("status"), "o horário antigo não é lembrado");
    }

    // ------------------------------------------------------------------ e-mail de teste e dados sensíveis

    @Test
    void comEmailDeTesteLigadoCadaNotificacaoDoPacienteGanhaUmaCopiaEnviada() {
        Sistema s = sistema(true, emailsEnviados::add);
        ObjectId id = agendarAs8h(s.agendamento());
        s.cancelamento().cancelar(id, null, SECRETARIA);

        var copias = notificacoes(Filters.eq("canal", "EMAIL_TESTE"));
        assertEquals(List.of("CANCELAMENTO_CONSULTA", "CONFIRMACAO_AGENDAMENTO", "LEMBRETE_CONSULTA"),
                copias.stream().map(n -> n.getString("tipo")).sorted().toList(), "médico não recebe e-mail");
        assertEquals(2, emailsEnviados.size(), "confirmação e cancelamento; o lembrete foi descartado antes da hora");
        assertTrue(copias.stream().allMatch(n -> PACIENTE.equals(n.getObjectId("destinatarioId"))));
    }

    @Test
    void emailQueFalhaTresVezesFicaComoFalhaEOSistemaContinua() {
        Sistema s = sistema(true, n -> { throw new IllegalStateException("Paciente sem e-mail cadastrado"); });
        agendarAs8h(s.agendamento());     // 1ª tentativa
        s.notificacoes().executarCiclo(); // 2ª
        s.notificacoes().executarCiclo(); // 3ª -> FALHA
        s.notificacoes().executarCiclo(); // não tenta de novo

        Document email = notificacoes(Filters.eq("canal", "EMAIL_TESTE"), Filters.eq("tipo", "CONFIRMACAO_AGENDAMENTO")).get(0);
        assertEquals("FALHA", email.getString("status"));
        assertEquals(3, email.getInteger("tentativas"));
        assertEquals("Paciente sem e-mail cadastrado", email.getString("erro"));
        assertEquals("ENVIADA", unica("CONFIRMACAO_AGENDAMENTO", Perfil.PACIENTE).getString("status"),
                "a falha do e-mail não afeta a notificação do sistema");
    }

    @Test
    void nenhumaMensagemExpoeCpfOuEmailDoPaciente() {
        Sistema s = sistema();
        ObjectId id = agendarAs8h(s.agendamento());
        s.cancelamento().cancelar(id, null, SECRETARIA);

        for (Document n : notificacoes()) {
            String texto = n.getString("titulo") + " " + n.getString("mensagem");
            assertFalse(texto.contains(CPF), texto);
            assertFalse(texto.contains("@"), texto);
            assertFalse(texto.contains("Paciente Teste"), "nem o nome do paciente vai para o médico: " + texto);
        }
    }

    @Test
    void mascaraOEmailNoLog() {
        assertEquals("p***@gmail.com", EntregaEmailTeste.NoLog.mascarar("paulo.silva@gmail.com"));
        assertEquals("***", EntregaEmailTeste.NoLog.mascarar("sem-arroba"));
    }
}
