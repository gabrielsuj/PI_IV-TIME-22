package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.AgendamentoRequest;
import br.edu.puccampinas.consultafacil.api.dto.FilaEsperaRequest;
import br.edu.puccampinas.consultafacil.api.dto.HistoricoEventoResponse;
import br.edu.puccampinas.consultafacil.api.dto.IndicadoresResponse;
import br.edu.puccampinas.consultafacil.config.MongoConnection;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.domain.enums.StatusConsulta;
import br.edu.puccampinas.consultafacil.repository.*;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import br.edu.puccampinas.consultafacil.service.*;
import br.edu.puccampinas.consultafacil.support.RelogioAjustavel;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Estados, histórico e indicadores integrados ao agendamento, ao cancelamento e à fila (#48),
 * com o MongoDB real e os validadores do banco, montados como no App.
 *
 * Um dia de atendimento da Dra. Fluxo (segunda 26/10/2026, 08:00–12:00 em São Paulo, consultas de 30 min = 8 vagas):
 *   A 08:00 paciente agenda, confirma pelo próprio login, médica conclui      -> CONCLUIDA
 *   B 08:30 secretaria agenda e confirma, paciente não vem                   -> FALTA
 *   C 09:00 secretaria cancela; D, que estava na fila, é avisado e agenda     -> CANCELADA
 *   D 09:00 agendada pela vaga da fila, fica sem desfecho                     -> AGENDADA
 * Indicadores esperados no dia: 4 consultas, ocupação 3/8, faltas 1/2, cancelamento 1/4,
 * confirmação 2/4 e preenchimento pela fila 1/3.
 *
 * Usa o banco consultafacil_test (scripts/recriar-banco.sh consultafacil_test) e só apaga os próprios dados (IDs f…).
 */
class FluxoEstadosHistoricoIndicadoresIntegrationTest {
    private static final ObjectId ESPECIALIDADE = new ObjectId("f00000000000000000000001");
    private static final ObjectId MEDICO = new ObjectId("f10000000000000000000001");
    private static final ObjectId A = new ObjectId("f40000000000000000000001");
    private static final ObjectId B = new ObjectId("f40000000000000000000002");
    private static final ObjectId C = new ObjectId("f40000000000000000000003");
    private static final ObjectId D = new ObjectId("f40000000000000000000004");
    private static final List<ObjectId> PACIENTES = List.of(A, B, C, D);

    private static final UsuarioLogado MEDICA = new UsuarioLogado("f70000000000000000000001", "Dra. Fluxo", Perfil.MEDICO);
    private static final UsuarioLogado SECRETARIA = new UsuarioLogado("f80000000000000000000001", "Secretaria", Perfil.SECRETARIA_ADMIN);
    private static final String DIA = "2026-10-26";

    private static MongoConnection mongo;
    private MongoDatabase db;
    private RelogioAjustavel relogio;
    private AgendamentoService agendamento;
    private StatusConsultaService status;
    private CancelamentoService cancelamento;
    private FilaEsperaService fila;
    private HistoricoConsultaService historico;
    private IndicadoresService indicadores;

    @BeforeAll static void conectar() {
        mongo = new MongoConnection("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000", "consultafacil_test");
    }

    @AfterAll static void fechar() { mongo.close(); }

    private static UsuarioLogado paciente(ObjectId id) {
        return new UsuarioLogado("f6" + id.toHexString().substring(2), "Paciente", Perfil.PACIENTE);
    }

    @BeforeEach void preparar() {
        db = mongo.database();
        db.getCollection("consultas").deleteMany(Filters.eq("medicoId", MEDICO));
        db.getCollection("historico").deleteMany(Filters.eq("medicoId", MEDICO));
        db.getCollection("filaEspera").deleteMany(Filters.in("pacienteId", PACIENTES));
        db.getCollection("notificacoes").deleteMany(Filters.in("destinatarioId", PACIENTES));
        db.getCollection("bloqueios").deleteMany(Filters.eq("medicoId", MEDICO));
        db.getCollection("agendas").deleteMany(Filters.eq("medicoId", MEDICO));

        salvar("especialidades", new Document("_id", ESPECIALIDADE)
                .append("codigo", "teste-fluxo-48").append("nome", "Clínica Teste Fluxo").append("ativa", true));
        salvar("medicos", new Document("_id", MEDICO).append("nome", "Dra. Fluxo")
                .append("crmNumero", "9900481").append("crmUf", "AP")
                .append("especialidadeIds", List.of(ESPECIALIDADE)).append("ativo", true)
                .append("usuarioId", new ObjectId(MEDICA.id())));
        for (int i = 0; i < PACIENTES.size(); i++) {
            ObjectId id = PACIENTES.get(i);
            salvar("pacientes", new Document("_id", id).append("nome", "Paciente Fluxo " + (char) ('A' + i))
                    .append("cpf", String.format("4800000000%d", i + 1)).append("email", "fluxo" + i + "@consultafacil.test")
                    .append("usuarioId", new ObjectId(paciente(id).id())));
        }
        db.getCollection("agendas").insertOne(new Document("medicoId", MEDICO)
                .append("fusoHorario", "America/Sao_Paulo").append("duracaoMinutos", 30)
                .append("jornadas", List.of(new Document("diaSemana", 1).append("faixas", List.of(
                        new Document("inicioMinuto", 480).append("fimMinuto", 720))))));

        // Mesmo encadeamento do App.java.
        relogio = new RelogioAjustavel(Instant.parse("2026-10-20T12:00:00Z"));
        var consultas = new ConsultaRepository(db);
        var historicoRepo = new HistoricoRepository.Mongo(db);
        var processor = new HistoricoProcessor(consultas, historicoRepo);
        var filaRepo = new FilaEsperaRepository(db);
        var nomes = new NomesRepository.Mongo(db);
        var pacienteDoUsuario = new PacienteDoUsuarioRepository(db);
        var medicoDoUsuario = new MedicoDoUsuarioRepository(db);
        var sinalizacao = new SinalizacaoFilaService(filaRepo, new NotificacaoRepository(db), consultas, nomes, processor, relogio);
        var registro = new RegistroConsultaService(consultas, processor, relogio, sinalizacao::sinalizar, sinalizacao::consultaAgendada);
        var acesso = new AcessoConsulta(pacienteDoUsuario::buscar, medicoDoUsuario::buscar);
        agendamento = new AgendamentoService(
                new DisponibilidadeService(new AgendaRepository(db), new BloqueioRepository(db), consultas, relogio),
                new MedicoRepository(db), new EspecialidadeRepository.Mongo(db), new PacienteRepository.Mongo(db),
                pacienteDoUsuario, registro);
        status = new StatusConsultaService(consultas, acesso, registro, relogio);
        cancelamento = new CancelamentoService(consultas, acesso, registro, AposCancelamento.NENHUM, relogio);
        fila = new FilaEsperaService(filaRepo, new EspecialidadeRepository.Mongo(db), new MedicoRepository(db),
                new PacienteRepository.Mongo(db), pacienteDoUsuario, nomes, relogio);
        historico = new HistoricoConsultaService(consultas, historicoRepo, acesso, nomes);
        indicadores = new IndicadoresService(new IndicadoresMongoRepository(db), new AcessoAgenda(medicoDoUsuario));
    }

    private void salvar(String colecao, Document d) {
        db.getCollection(colecao).replaceOne(Filters.eq("_id", d.get("_id")), d, new ReplaceOptions().upsert(true));
    }

    /** horaLocal "08:00" em São Paulo (UTC−3). */
    private ObjectId agendar(ObjectId paciente, String horaLocal, UsuarioLogado ator) {
        Instant inicio = Instant.parse(DIA + "T" + horaLocal + ":00Z").plus(Duration.ofHours(3));
        var pedido = new AgendamentoRequest(ator.perfil() == Perfil.PACIENTE ? null : paciente.toHexString(),
                MEDICO.toHexString(), ESPECIALIDADE.toHexString(), inicio.toString(), inicio.plus(Duration.ofMinutes(30)).toString());
        return new ObjectId(agendamento.agendar(pedido, ator).id());
    }

    private List<String> eventos(ObjectId consulta) {
        return historico.listar(consulta, SECRETARIA).eventos().stream().map(HistoricoEventoResponse::evento).toList();
    }

    private static void recusa(int esperado, Executable acao) {
        assertEquals(esperado, assertThrows(ApiException.class, acao).status());
    }

    @Test
    void umDiaDeAtendimentoAtualizaEstadosHistoricoEIndicadores() {
        // --- Antes do dia: agendamentos, confirmações e fila
        ObjectId a = agendar(A, "08:00", paciente(A));
        ObjectId b = agendar(B, "08:30", SECRETARIA);
        ObjectId c = agendar(C, "09:00", SECRETARIA);
        fila.inscrever(new FilaEsperaRequest(null, ESPECIALIDADE.toHexString(), MEDICO.toHexString(), "MANHA", null, null), paciente(D));

        status.alterar(a, StatusConsulta.CONFIRMADA, paciente(A));
        status.alterar(b, StatusConsulta.CONFIRMADA, SECRETARIA);
        recusa(403, () -> status.alterar(c, StatusConsulta.CONFIRMADA, paciente(A)));       // consulta de outro paciente
        recusa(409, () -> status.alterar(a, StatusConsulta.CONCLUIDA, MEDICA));            // ainda não terminou
        recusa(409, () -> status.alterar(c, StatusConsulta.CONCLUIDA, SECRETARIA));        // não foi confirmada

        // --- Cancelamento libera a vaga e a fila avisa D, que agenda o mesmo horário
        cancelamento.cancelar(c, "Viagem", SECRETARIA);
        Document inscricao = db.getCollection("filaEspera").find(Filters.eq("pacienteId", D)).first();
        assertEquals("SINALIZADA", inscricao.getString("status"));
        ObjectId d = agendar(D, "09:00", paciente(D));
        inscricao = db.getCollection("filaEspera").find(Filters.eq("pacienteId", D)).first();
        assertEquals("ATENDIDA", inscricao.getString("status"));
        assertEquals(d, inscricao.getObjectId("consultaAgendadaId"));

        // --- Depois do atendimento (26/10 às 13:00 em São Paulo)
        relogio.avancar(Duration.between(relogio.instant(), Instant.parse(DIA + "T16:00:00Z")));
        recusa(403, () -> status.alterar(a, StatusConsulta.CONCLUIDA, paciente(A)));       // paciente não conclui
        status.alterar(a, StatusConsulta.CONCLUIDA, MEDICA);
        status.alterar(b, StatusConsulta.FALTA, SECRETARIA);
        recusa(409, () -> status.alterar(a, StatusConsulta.FALTA, MEDICA));                // status final
        recusa(409, () -> status.alterar(d, StatusConsulta.CONCLUIDA, MEDICA));            // pulou a confirmação
        recusa(409, () -> cancelamento.cancelar(d, null, paciente(D)));                    // já começou

        // --- Histórico: cada mudança aceita virou um evento; as recusadas não deixaram rastro
        assertEquals(List.of("CRIACAO", "CONFIRMACAO", "CONCLUSAO"), eventos(a));
        assertEquals(List.of("CRIACAO", "CONFIRMACAO", "FALTA"), eventos(b));
        assertEquals(List.of("CRIACAO", "CANCELAMENTO", "SINALIZACAO_FILA"), eventos(c));
        assertEquals(List.of("CRIACAO"), eventos(d));
        var doA = historico.listar(a, paciente(A)).eventos();
        assertEquals(List.of("PACIENTE", "PACIENTE", "MEDICO"), doA.stream().map(e -> e.ator().perfil()).toList());
        var doC = historico.listar(c, SECRETARIA).eventos();
        assertEquals("Viagem", doC.get(1).motivo());
        assertEquals(1, doC.get(2).totalSinalizados());
        recusa(403, () -> historico.listar(a, paciente(B)));                                // histórico de outro paciente

        // --- Indicadores do dia, pela médica e pela secretaria
        for (UsuarioLogado quem : List.of(MEDICA, SECRETARIA)) {
            IndicadoresResponse r = indicadores.consultar(DIA, DIA, quem == MEDICA ? null : MEDICO.toHexString(), quem);
            var n = r.contagens();
            assertEquals(List.of(4L, 1L, 0L, 1L, 1L, 1L), List.of(n.totalConsultas(), n.agendadas(), n.confirmadas(),
                    n.concluidas(), n.faltas(), n.canceladas()), "contagens por status");
            assertEquals(8, n.capacidade());
            assertEquals(3, n.vagasOcupadas());
            assertEquals(2, n.comConfirmacaoRegistrada(), "A e B confirmaram; D ainda não");
            assertEquals(1, n.preenchidasPelaFila(), "a vaga de C ficou com D pela fila");
            assertTaxa(r.ocupacao(), 3, 8, "37.50");
            assertTaxa(r.faltas(), 1, 2, "50.00");
            assertTaxa(r.cancelamento(), 1, 4, "25.00");
            assertTaxa(r.confirmacao(), 2, 4, "50.00");
            assertTaxa(r.preenchimentoFila(), 1, 3, "33.33");
        }
        recusa(403, () -> indicadores.consultar(DIA, DIA, MEDICO.toHexString(), paciente(A)));
    }

    private static void assertTaxa(IndicadoresResponse.Taxa taxa, long numerador, long denominador, String percentual) {
        assertEquals(IndicadoresResponse.Situacao.DISPONIVEL, taxa.situacao());
        assertEquals(numerador, taxa.numerador());
        assertEquals(denominador, taxa.denominador());
        assertEquals(new BigDecimal(percentual), taxa.percentual());
    }
}
