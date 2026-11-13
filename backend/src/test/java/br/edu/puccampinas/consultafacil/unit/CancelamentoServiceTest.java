package br.edu.puccampinas.consultafacil.unit;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.ConsultaResponse;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import br.edu.puccampinas.consultafacil.service.AposCancelamento;
import br.edu.puccampinas.consultafacil.service.CancelamentoService;
import br.edu.puccampinas.consultafacil.service.HistoricoProcessor;
import br.edu.puccampinas.consultafacil.service.RegistroConsultaService;
import br.edu.puccampinas.consultafacil.support.ConsultasEmMemoria;
import br.edu.puccampinas.consultafacil.support.RelogioAjustavel;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Regras do cancelamento (#27) sem banco: acesso por perfil, status, prazo, motivo, histórico e gancho da fila. */
class CancelamentoServiceTest {
    private static final Instant AGORA = Instant.parse("2026-11-13T12:00:00Z");
    private static final Instant AMANHA_9H = Instant.parse("2026-11-14T12:00:00Z");   // 09:00 em São Paulo

    private static final ObjectId PAULO = new ObjectId("b40000000000000000000001");
    private static final ObjectId CARLA = new ObjectId("b40000000000000000000002");
    private static final ObjectId DRA_ANA = new ObjectId("b10000000000000000000001");
    private static final ObjectId DR_BRUNO = new ObjectId("b10000000000000000000002");

    private static final UsuarioLogado PAULO_LOGADO = usuario("b60000000000000000000001", Perfil.PACIENTE);
    private static final UsuarioLogado CARLA_LOGADA = usuario("b60000000000000000000002", Perfil.PACIENTE);
    private static final UsuarioLogado PACIENTE_SEM_CADASTRO = usuario("b60000000000000000000009", Perfil.PACIENTE);
    private static final UsuarioLogado ANA_LOGADA = usuario("b70000000000000000000001", Perfil.MEDICO);
    private static final UsuarioLogado BRUNO_LOGADO = usuario("b70000000000000000000002", Perfil.MEDICO);
    private static final UsuarioLogado SECRETARIA = usuario("b80000000000000000000001", Perfil.SECRETARIA_ADMIN);

    private final ConsultasEmMemoria consultas = new ConsultasEmMemoria();
    private final List<Document> historico = new ArrayList<>();
    private final List<Document> avisosFila = new ArrayList<>();
    private final RelogioAjustavel relogio = new RelogioAjustavel(AGORA);
    private final RegistroConsultaService registro = new RegistroConsultaService(consultas,
            new HistoricoProcessor(consultas, historico::add), relogio);

    private final Map<String, ObjectId> pacientesPorLogin = Map.of(PAULO_LOGADO.id(), PAULO, CARLA_LOGADA.id(), CARLA);
    private final Map<String, ObjectId> medicosPorLogin = Map.of(ANA_LOGADA.id(), DRA_ANA, BRUNO_LOGADO.id(), DR_BRUNO);

    private CancelamentoService criarService(AposCancelamento fila) {
        return new CancelamentoService(consultas,
                login -> Optional.ofNullable(pacientesPorLogin.get(login)),
                login -> Optional.ofNullable(medicosPorLogin.get(login)),
                registro, fila, relogio);
    }

    private final CancelamentoService service = criarService(avisosFila::add);

    /** Consulta do Paulo com a Dra. Ana amanhã às 9h, já gravada no banco. */
    private ObjectId consultaDoPaulo(String status) {
        ObjectId id = new ObjectId();
        consultas.salvar(new Document("_id", id).append("pacienteId", PAULO).append("medicoId", DRA_ANA)
                .append("especialidadeId", new ObjectId("b20000000000000000000001"))
                .append("inicio", Date.from(AMANHA_9H)).append("fim", Date.from(AMANHA_9H.plus(Duration.ofMinutes(30))))
                .append("status", status).append("criadaEm", Date.from(AGORA.minus(Duration.ofDays(3)))).append("versao", 0));
        return id;
    }

    private static UsuarioLogado usuario(String id, Perfil perfil) { return new UsuarioLogado(id, "Teste " + perfil, perfil); }

    private static int status(Runnable acao) {
        return assertThrows(ApiException.class, acao::run).status();
    }

    // ------------------------------------------------------------------ quem pode cancelar

    @Test void pacienteCancelaAPropriaConsulta() {
        ObjectId id = consultaDoPaulo("AGENDADA");

        ConsultaResponse r = service.cancelar(id, "Imprevisto no trabalho", PAULO_LOGADO);

        assertEquals("CANCELADA", r.status());
        assertEquals(id.toHexString(), r.id());
        assertEquals("CANCELADA", consultas.docs.get(id).getString("status"));
        assertEquals("Imprevisto no trabalho", consultas.docs.get(id).getString("motivoCancelamento"));
    }

    @Test void pacienteNaoCancelaConsultaDeOutroPaciente() {
        ObjectId id = consultaDoPaulo("AGENDADA");
        assertEquals(403, status(() -> service.cancelar(id, null, CARLA_LOGADA)));
        assertEquals("AGENDADA", consultas.docs.get(id).getString("status"));
    }

    @Test void loginDePacienteSemCadastroRecebe403() {
        ObjectId id = consultaDoPaulo("AGENDADA");
        assertEquals(403, status(() -> service.cancelar(id, null, PACIENTE_SEM_CADASTRO)));
    }

    @Test void medicoCancelaConsultaQueEleAtende() {
        ObjectId id = consultaDoPaulo("CONFIRMADA");
        assertEquals("CANCELADA", service.cancelar(id, null, ANA_LOGADA).status());
    }

    @Test void medicoNaoCancelaConsultaDeOutroMedico() {
        ObjectId id = consultaDoPaulo("AGENDADA");
        assertEquals(403, status(() -> service.cancelar(id, null, BRUNO_LOGADO)));
    }

    @Test void secretariaCancelaQualquerConsulta() {
        ObjectId id = consultaDoPaulo("AGENDADA");
        assertEquals("CANCELADA", service.cancelar(id, null, SECRETARIA).status());
    }

    @Test void consultaInexistenteRecebe404() {
        assertEquals(404, status(() -> service.cancelar(new ObjectId(), null, SECRETARIA)));
    }

    // ------------------------------------------------------------------ status e prazo (opção 1)

    @ParameterizedTest(name = "consulta {0} não pode ser cancelada")
    @ValueSource(strings = {"CANCELADA", "CONCLUIDA", "FALTA"})
    void statusFinalRecebe409(String final_) {
        ObjectId id = consultaDoPaulo(final_);
        assertEquals(409, status(() -> service.cancelar(id, null, SECRETARIA)));
        assertTrue(historico.isEmpty());
    }

    @Test void cancelarDuasVezesRecebe409NaSegunda() {
        ObjectId id = consultaDoPaulo("AGENDADA");
        service.cancelar(id, null, PAULO_LOGADO);
        assertEquals(409, status(() -> service.cancelar(id, null, PAULO_LOGADO)));
        assertEquals(1, historico.size());
    }

    @Test void consultaQueJaComecouNaoPodeSerCanceladaNemPelaSecretaria() {
        ObjectId id = consultaDoPaulo("AGENDADA");
        relogio.avancar(Duration.ofDays(1));          // agora = exatamente o início da consulta

        ApiException e = assertThrows(ApiException.class, () -> service.cancelar(id, null, SECRETARIA));
        assertEquals(409, e.status());
        assertEquals(CancelamentoService.JA_COMECOU, e.getMessage());
        assertEquals("AGENDADA", consultas.docs.get(id).getString("status"));
    }

    @Test void umMinutoAntesDoInicioAindaPodeCancelar() {
        ObjectId id = consultaDoPaulo("AGENDADA");
        relogio.avancar(Duration.ofDays(1).minusMinutes(1));
        assertEquals("CANCELADA", service.cancelar(id, null, PAULO_LOGADO).status());
    }

    // ------------------------------------------------------------------ motivo e histórico

    @Test void registraCancelamentoNoHistoricoComAtorEMotivo() {
        ObjectId id = consultaDoPaulo("CONFIRMADA");
        service.cancelar(id, "  Viagem  ", PAULO_LOGADO);

        assertEquals(1, historico.size());
        Document e = historico.get(0);
        assertEquals("CANCELAMENTO", e.getString("evento"));
        assertEquals("CONFIRMADA", e.getString("statusAnterior"));
        assertEquals("CANCELADA", e.getString("statusNovo"));
        assertEquals("Viagem", e.getString("motivo"));                 // espaços removidos
        assertEquals("PACIENTE", e.get("ator", Document.class).getString("perfil"));
        assertEquals(Date.from(AGORA), e.getDate("ocorridoEm"));
    }

    @Test void motivoEmBrancoFicaAusente() {
        ObjectId id = consultaDoPaulo("AGENDADA");
        service.cancelar(id, "   ", PAULO_LOGADO);
        assertFalse(consultas.docs.get(id).containsKey("motivoCancelamento"));
        assertFalse(historico.get(0).containsKey("motivo"));
    }

    @Test void motivoMaiorQue255CaracteresRecebe400() {
        ObjectId id = consultaDoPaulo("AGENDADA");
        assertEquals(400, status(() -> service.cancelar(id, "x".repeat(256), PAULO_LOGADO)));
        assertEquals("AGENDADA", consultas.docs.get(id).getString("status"));
    }

    // ------------------------------------------------------------------ gancho da fila (#32)

    @Test void avisaAFilaUmaVezComAConsultaCancelada() {
        ObjectId id = consultaDoPaulo("AGENDADA");
        service.cancelar(id, null, PAULO_LOGADO);

        assertEquals(1, avisosFila.size());
        assertEquals(id, avisosFila.get(0).getObjectId("_id"));
        assertEquals("CANCELADA", avisosFila.get(0).getString("status"));
    }

    @Test void naoAvisaAFilaQuandoOCancelamentoEhRecusado() {
        ObjectId id = consultaDoPaulo("AGENDADA");
        assertThrows(ApiException.class, () -> service.cancelar(id, null, CARLA_LOGADA));
        assertTrue(avisosFila.isEmpty());
    }

    @Test void falhaNaFilaNaoDesfazOCancelamento() {
        ObjectId id = consultaDoPaulo("AGENDADA");
        CancelamentoService comFilaQuebrada = criarService(c -> { throw new IllegalStateException("fila fora do ar"); });

        assertEquals("CANCELADA", assertDoesNotThrow(() -> comFilaQuebrada.cancelar(id, null, PAULO_LOGADO)).status());
        assertEquals("CANCELADA", consultas.docs.get(id).getString("status"));
    }
}
