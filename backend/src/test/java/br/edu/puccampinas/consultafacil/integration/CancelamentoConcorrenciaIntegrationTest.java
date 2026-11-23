/*
Autoria: Murilo Moraes
RA: 25000073
*/
package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.AgendamentoRequest;
import br.edu.puccampinas.consultafacil.api.dto.FilaEsperaRequest;
import br.edu.puccampinas.consultafacil.config.AppConfig;
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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cancelamento sob concorrência (#40), com o MongoDB real, a fila (#32) e as notificações (#34) ligadas como no App.
 *
 * Situações do balcão: o paciente cancela pelo celular enquanto a secretaria cancela pelo sistema, ou o paciente
 * confirma no mesmo instante em que a clínica cancela. Em qualquer ordem, deve sobrar um único desfecho, com
 * histórico em sequência, a fila avisada uma vez só e uma notificação de cancelamento por destinatário.
 *
 * Usa o banco consultafacil_test (scripts/recriar-banco.sh consultafacil_test) e só apaga os próprios dados (IDs ab…).
 * Relógio em 20/10/2026 12:00Z; as consultas são na segunda 26/10, entre 08:00 e 18:00 de São Paulo.
 */
class CancelamentoConcorrenciaIntegrationTest {
    private static final ObjectId ESPECIALIDADE = new ObjectId("ab0000000000000000000001");
    private static final ObjectId MEDICO = new ObjectId("ab1000000000000000000001");
    private static final ObjectId PACIENTE = new ObjectId("ab4000000000000000000001");
    private static final ObjectId INTERESSADO = new ObjectId("ab4000000000000000000002");
    private static final List<ObjectId> PACIENTES = List.of(PACIENTE, INTERESSADO);

    private static final UsuarioLogado PACIENTE_LOGADO = new UsuarioLogado("ab6000000000000000000001", "Paciente", Perfil.PACIENTE);
    private static final UsuarioLogado INTERESSADO_LOGADO = new UsuarioLogado("ab6000000000000000000002", "Interessado", Perfil.PACIENTE);
    private static final UsuarioLogado MEDICA = new UsuarioLogado("ab7000000000000000000001", "Médica", Perfil.MEDICO);
    private static final UsuarioLogado SECRETARIA = new UsuarioLogado("ab8000000000000000000001", "Secretaria", Perfil.SECRETARIA_ADMIN);
    private static final int RODADAS = 15;

    private static MongoConnection mongo;
    private MongoDatabase db;
    private AgendamentoService agendamento;
    private CancelamentoService cancelamento;
    private StatusConsultaService status;
    private FilaEsperaService fila;

    @BeforeAll static void conectar() {
        mongo = new MongoConnection("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000", "consultafacil_test");
    }

    @AfterAll static void fechar() { mongo.close(); }

    @BeforeEach void preparar() {
        db = mongo.database();
        db.getCollection("consultas").deleteMany(Filters.eq("medicoId", MEDICO));
        db.getCollection("historico").deleteMany(Filters.eq("medicoId", MEDICO));
        db.getCollection("filaEspera").deleteMany(Filters.in("pacienteId", PACIENTES));
        db.getCollection("notificacoes").deleteMany(Filters.in("destinatarioId", List.of(PACIENTE, INTERESSADO, MEDICO)));
        db.getCollection("bloqueios").deleteMany(Filters.eq("medicoId", MEDICO));
        db.getCollection("agendas").deleteMany(Filters.eq("medicoId", MEDICO));

        salvar("especialidades", new Document("_id", ESPECIALIDADE)
                .append("codigo", "teste-concorrencia-40").append("nome", "Clínica Teste Concorrência").append("ativa", true));
        salvar("medicos", new Document("_id", MEDICO).append("nome", "Dra. Concorrência")
                .append("crmNumero", "9900401").append("crmUf", "AP")
                .append("especialidadeIds", List.of(ESPECIALIDADE)).append("ativo", true)
                .append("usuarioId", new ObjectId(MEDICA.id())));
        salvar("pacientes", new Document("_id", PACIENTE).append("nome", "Paciente Concorrência")
                .append("cpf", "40000000001").append("email", "concorrencia1@consultafacil.test")
                .append("usuarioId", new ObjectId(PACIENTE_LOGADO.id())));
        salvar("pacientes", new Document("_id", INTERESSADO).append("nome", "Interessado Concorrência")
                .append("cpf", "40000000002").append("email", "concorrencia2@consultafacil.test")
                .append("usuarioId", new ObjectId(INTERESSADO_LOGADO.id())));
        db.getCollection("agendas").insertOne(new Document("medicoId", MEDICO)
                .append("fusoHorario", "America/Sao_Paulo").append("duracaoMinutos", 30)
                .append("jornadas", List.of(new Document("diaSemana", 1).append("faixas", List.of(
                        new Document("inicioMinuto", 480).append("fimMinuto", 1080))))));

        // Mesmo encadeamento do App.java (#27, #32, #34).
        var relogio = new RelogioAjustavel(Instant.parse("2026-10-20T12:00:00Z"));
        var consultas = new ConsultaRepository(db);
        var processor = new HistoricoProcessor(consultas, new HistoricoRepository.Mongo(db));
        var filaRepo = new FilaEsperaRepository(db);
        var nomes = new NomesRepository.Mongo(db);
        var notificacoesRepo = new NotificacaoRepository(db);
        var pacienteDoUsuario = new PacienteDoUsuarioRepository(db);
        var disponibilidade = new DisponibilidadeService(new AgendaRepository(db), new BloqueioRepository(db), consultas, relogio);
        var sinalizacao = new SinalizacaoFilaService(filaRepo, notificacoesRepo, consultas, nomes, processor, relogio, disponibilidade);
        var notificacoes = new NotificacoesService(notificacoesRepo, consultas, nomes, n -> {},
                new AppConfig.Notificacoes(24, 60, false), relogio);
        var registro = new RegistroConsultaService(consultas, processor, relogio, sinalizacao::sinalizar, consulta -> {
            notificacoes.consultaAgendada(consulta);
            sinalizacao.consultaAgendada(consulta);
        });
        var acesso = new AcessoConsulta(pacienteDoUsuario::buscar, new MedicoDoUsuarioRepository(db)::buscar);
        agendamento = new AgendamentoService(
                disponibilidade,
                new MedicoRepository(db), new EspecialidadeRepository.Mongo(db), new PacienteRepository.Mongo(db),
                pacienteDoUsuario, registro);
        cancelamento = new CancelamentoService(consultas, acesso, registro, notificacoes::consultaCancelada, relogio);
        status = new StatusConsultaService(consultas, acesso, registro, relogio);
        fila = new FilaEsperaService(filaRepo, new EspecialidadeRepository.Mongo(db), new MedicoRepository(db),
                new PacienteRepository.Mongo(db), pacienteDoUsuario, nomes, relogio);
    }

    private void salvar(String colecao, Document d) {
        db.getCollection(colecao).replaceOne(Filters.eq("_id", d.get("_id")), d, new ReplaceOptions().upsert(true));
    }

    /** n-ésima vaga de 30 min da segunda 26/10 a partir das 08:00 de São Paulo (11:00Z). */
    private ObjectId agendar(int n) {
        Instant inicio = Instant.parse("2026-10-26T11:00:00Z").plus(Duration.ofMinutes(30L * n));
        return new ObjectId(agendamento.agendar(new AgendamentoRequest(null, MEDICO.toHexString(), ESPECIALIDADE.toHexString(),
                inicio.toString(), inicio.plus(Duration.ofMinutes(30)).toString()), PACIENTE_LOGADO).id());
    }

    /** Dispara as ações ao mesmo tempo (mesma largada) e devolve o status HTTP de cada uma (200 = aceita). */
    private static List<Integer> aoMesmoTempo(List<Callable<?>> acoes) throws Exception {
        var largada = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> resultados = new ArrayList<>();
            for (Callable<?> acao : acoes)
                resultados.add(pool.submit(() -> {
                    largada.await();
                    try { acao.call(); return 200; }
                    catch (ApiException e) { return e.status(); }
                }));
            largada.countDown();
            List<Integer> status = new ArrayList<>();
            for (Future<Integer> r : resultados) status.add(r.get());
            return status;
        }
    }

    private List<Document> historico(ObjectId consulta) {
        return db.getCollection("historico").find(Filters.eq("consultaId", consulta))
                .sort(Sorts.ascending("sequencia")).into(new ArrayList<>());
    }

    /** Sequência 1..n, cada evento parte do status em que o anterior terminou e o último é o status gravado. */
    private void assertHistoricoCoerente(ObjectId consulta) {
        List<Document> eventos = historico(consulta);
        Document atual = db.getCollection("consultas").find(Filters.eq("_id", consulta)).first();
        for (int i = 0; i < eventos.size(); i++) {
            assertEquals(i + 1, eventos.get(i).getInteger("sequencia"), "sequência sem buracos nem repetição");
            if (i > 0) assertEquals(eventos.get(i - 1).getString("statusNovo"), eventos.get(i).getString("statusAnterior"));
        }
        assertEquals(atual.getString("status"), eventos.getLast().getString("statusNovo"));
        assertTrue(atual.getList("eventosPendentes", Document.class, List.of()).isEmpty(), "nenhum evento ficou para trás");
    }

    private long contar(String colecao, org.bson.conversions.Bson filtro) {
        return db.getCollection(colecao).countDocuments(filtro);
    }

    @Test
    void tresCancelamentosSimultaneosDaMesmaConsultaViramUmSo() throws Exception {
        fila.inscrever(new FilaEsperaRequest(null, ESPECIALIDADE.toHexString(), MEDICO.toHexString(), "QUALQUER", null, null),
                INTERESSADO_LOGADO);
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            ObjectId id = agendar(rodada);

            List<Integer> respostas = aoMesmoTempo(List.of(
                    () -> cancelamento.cancelar(id, "Pelo celular", PACIENTE_LOGADO),
                    () -> cancelamento.cancelar(id, "No balcão", SECRETARIA),
                    () -> cancelamento.cancelar(id, null, MEDICA)));

            String onde = "rodada " + rodada + " " + respostas;
            assertEquals(1, respostas.stream().filter(s -> s == 200).count(), "só um cancelamento é aceito, " + onde);
            assertTrue(respostas.stream().allMatch(s -> s == 200 || s == 409), "os outros recebem 409, " + onde);
            assertEquals(List.of("CRIACAO", "CANCELAMENTO", "SINALIZACAO_FILA"),
                    historico(id).stream().map(e -> e.getString("evento")).toList(), onde);
            assertHistoricoCoerente(id);
            // A vaga é oferecida à fila no máximo uma vez. Na primeira rodada o interessado está aguardando e é avisado;
            // depois ele fica SINALIZADO e não é avisado de novo.
            long avisosFila = contar("notificacoes", Filters.and(Filters.eq("consultaId", id), Filters.eq("tipo", "VAGA_LIBERADA")));
            assertEquals(rodada == 0 ? 1 : 0, avisosFila, onde);
            // Uma notificação de cancelamento por destinatário, e nunca para quem cancelou (#34).
            List<Document> avisos = db.getCollection("notificacoes").find(Filters.and(Filters.eq("consultaId", id),
                    Filters.eq("tipo", "CANCELAMENTO_CONSULTA"))).into(new ArrayList<>());
            assertEquals(avisos.stream().map(n -> n.getString("destinatarioPerfil")).distinct().count(), avisos.size(), onde);
            assertTrue(avisos.size() >= 1 && avisos.size() <= 2, onde);
            // O lembrete da consulta foi descartado.
            assertEquals(0, contar("notificacoes", Filters.and(Filters.eq("consultaId", id),
                    Filters.eq("tipo", "LEMBRETE_CONSULTA"), Filters.eq("status", "AGENDADA"))), onde);
        }
    }

    @Test
    void confirmarECancelarAoMesmoTempoTerminaEmUmEstadoValido() throws Exception {
        int cancelada = 0, confirmada = 0;
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            ObjectId id = agendar(rodada);

            List<Integer> respostas = aoMesmoTempo(List.of(
                    () -> status.alterar(id, StatusConsulta.CONFIRMADA, PACIENTE_LOGADO),
                    () -> cancelamento.cancelar(id, "Médica indisponível", SECRETARIA)));

            String onde = "rodada " + rodada + " " + respostas;
            assertTrue(respostas.stream().allMatch(s -> s == 200 || s == 409), onde);
            List<String> eventos = historico(id).stream().map(e -> e.getString("evento")).toList();
            String final_ = db.getCollection("consultas").find(Filters.eq("_id", id)).first().getString("status");
            // Ordens possíveis: confirma e depois cancela; cancela e a confirmação é recusada; confirma e o
            // cancelamento perde a corrida (409). Nunca as duas aceitas sem as duas no histórico.
            switch (final_) {
                case "CANCELADA" -> {
                    cancelada++;
                    assertTrue(eventos.equals(List.of("CRIACAO", "CANCELAMENTO", "SINALIZACAO_FILA"))
                            || eventos.equals(List.of("CRIACAO", "CONFIRMACAO", "CANCELAMENTO", "SINALIZACAO_FILA")), onde + eventos);
                    assertEquals(eventos.contains("CONFIRMACAO") ? List.of(200, 200) : List.of(409, 200), respostas, onde);
                }
                case "CONFIRMADA" -> {
                    confirmada++;
                    assertEquals(List.of("CRIACAO", "CONFIRMACAO"), eventos, onde);
                    assertEquals(List.of(200, 409), respostas, onde);
                }
                default -> fail("status inesperado " + final_ + ", " + onde);
            }
            assertHistoricoCoerente(id);
        }
        assertEquals(RODADAS, cancelada + confirmada);
    }
}
