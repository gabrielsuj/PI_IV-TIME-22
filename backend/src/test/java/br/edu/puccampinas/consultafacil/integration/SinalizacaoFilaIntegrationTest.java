package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.config.MongoConnection;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.repository.ConsultaRepository;
import br.edu.puccampinas.consultafacil.repository.FilaEsperaRepository;
import br.edu.puccampinas.consultafacil.repository.HistoricoRepository;
import br.edu.puccampinas.consultafacil.repository.NomesRepository;
import br.edu.puccampinas.consultafacil.repository.NotificacaoRepository;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import br.edu.puccampinas.consultafacil.service.HistoricoProcessor;
import br.edu.puccampinas.consultafacil.service.RegistroConsultaService;
import br.edu.puccampinas.consultafacil.service.SinalizacaoFilaService;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cancelamento -> vaga liberada -> histórico -> fila sinalizada (#32), com o MongoDB real e os validadores do banco.
 *
 * Os interesses da fila são gravados direto na coleção (o cadastro pela API é a #31).
 * Usa o banco consultafacil_test e só apaga os próprios dados (IDs com prefixo c…).
 * Relógio parado em 20/10/2026 12:00Z; a vaga é segunda 26/10 às 10:00 em São Paulo (13:00Z).
 */
class SinalizacaoFilaIntegrationTest {
    private static final ObjectId ESPECIALIDADE = new ObjectId("c00000000000000000000001");
    private static final ObjectId OUTRA_ESPECIALIDADE = new ObjectId("c00000000000000000000002");
    private static final ObjectId MEDICO = new ObjectId("c10000000000000000000001");
    private static final ObjectId OUTRO_MEDICO = new ObjectId("c10000000000000000000002");
    private static final List<ObjectId> PACIENTES = new ArrayList<>();
    private static final Instant AGORA = Instant.parse("2026-10-20T12:00:00Z");
    private static final Instant VAGA = Instant.parse("2026-10-26T13:00:00Z"); // 10:00 em São Paulo
    private static final UsuarioLogado SECRETARIA =
            new UsuarioLogado(new ObjectId().toHexString(), "Secretaria teste", Perfil.SECRETARIA_ADMIN);

    static {
        for (int i = 1; i <= 10; i++) PACIENTES.add(new ObjectId(String.format("c4%022x", i)));
    }

    private static MongoConnection mongo;
    private MongoDatabase db;
    private RegistroConsultaService registro;

    @BeforeAll static void conectar() {
        mongo = new MongoConnection("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000", "consultafacil_test");
    }

    @AfterAll static void fechar() { mongo.close(); }

    @BeforeEach void preparar() {
        db = mongo.database();
        var medicos = Filters.in("medicoId", MEDICO, OUTRO_MEDICO);
        db.getCollection("consultas").deleteMany(medicos);
        db.getCollection("historico").deleteMany(medicos);
        db.getCollection("filaEspera").deleteMany(Filters.in("pacienteId", PACIENTES));
        db.getCollection("notificacoes").deleteMany(Filters.in("destinatarioId", PACIENTES));
        salvar("especialidades", new Document("_id", ESPECIALIDADE)
                .append("codigo", "teste-fila").append("nome", "Fila Teste").append("ativa", true));
        salvar("especialidades", new Document("_id", OUTRA_ESPECIALIDADE)
                .append("codigo", "teste-fila-2").append("nome", "Fila Teste 2").append("ativa", true));
        salvar("medicos", new Document("_id", MEDICO).append("nome", "Dra. Fila Teste")
                .append("crmNumero", "9700001").append("crmUf", "RR")
                .append("especialidadeIds", List.of(ESPECIALIDADE)).append("ativo", true));
        salvar("medicos", new Document("_id", OUTRO_MEDICO).append("nome", "Dr. Outro Fila")
                .append("crmNumero", "9700002").append("crmUf", "RR")
                .append("especialidadeIds", List.of(ESPECIALIDADE)).append("ativo", true));
        for (int i = 0; i < PACIENTES.size(); i++)
            salvar("pacientes", new Document("_id", PACIENTES.get(i)).append("nome", "Paciente Fila " + (i + 1))
                    .append("cpf", String.format("970000000%02d", i + 1)).append("email", "fila" + (i + 1) + "@consultafacil.test"));

        Clock relogio = Clock.fixed(AGORA, ZoneOffset.UTC);
        var consultas = new ConsultaRepository(db);
        var historico = new HistoricoProcessor(consultas, new HistoricoRepository.Mongo(db));
        var sinalizacao = new SinalizacaoFilaService(new FilaEsperaRepository(db), new NotificacaoRepository(db),
                consultas, new NomesRepository.Mongo(db), historico, relogio);
        registro = new RegistroConsultaService(consultas, historico, relogio, sinalizacao::sinalizar);
    }

    @Test void deveLiberarVagaRegistrarHistoricoESinalizarFila() {
        ObjectId consulta = agendar(PACIENTES.get(0), MEDICO, VAGA);
        ObjectId ocupado = PACIENTES.get(7);
        agendar(ocupado, OUTRO_MEDICO, VAGA); // já tem consulta no mesmo horário com outro médico

        ObjectId qualquerMedico = interesse(PACIENTES.get(1), ESPECIALIDADE, null, "QUALQUER", 1, null, null, 90);
        ObjectId medicoDaVagaManha = interesse(PACIENTES.get(2), ESPECIALIDADE, MEDICO, "MANHA", 2, null, null, 90);
        List<ObjectId> naoCompativeis = List.of(
                interesse(PACIENTES.get(3), ESPECIALIDADE, MEDICO, "TARDE", 3, null, null, 90),          // turno
                interesse(PACIENTES.get(4), OUTRA_ESPECIALIDADE, null, "QUALQUER", 4, null, null, 90),  // especialidade
                interesse(PACIENTES.get(5), ESPECIALIDADE, OUTRO_MEDICO, "QUALQUER", 5, null, null, 90), // médico
                interesse(PACIENTES.get(6), ESPECIALIDADE, null, "QUALQUER", 6, null, null, 3),          // vencido
                interesse(ocupado, ESPECIALIDADE, null, "QUALQUER", 7, null, null, 90),                  // sobreposição
                interesse(PACIENTES.get(8), ESPECIALIDADE, null, "QUALQUER", 8,                           // janela
                        VAGA.plus(Duration.ofDays(1)), VAGA.plus(Duration.ofDays(10)), 90));

        Document cancelada = cancelar(consulta);
        assertEquals("CANCELADA", cancelada.getString("status"));

        // Vaga liberada: outro paciente consegue reservar o mesmo horário com o mesmo médico.
        assertDoesNotThrow(() -> agendar(PACIENTES.get(9), MEDICO, VAGA));

        // Histórico: CRIACAO, CANCELAMENTO e SINALIZACAO_FILA em sequência, pelo SISTEMA, com o total.
        List<Document> eventos = historico(consulta);
        assertEquals(List.of("CRIACAO", "CANCELAMENTO", "SINALIZACAO_FILA"), eventos.stream().map(e -> e.getString("evento")).toList());
        assertEquals(List.of(1, 2, 3), eventos.stream().map(e -> e.getInteger("sequencia")).toList());
        Document sinalizacao = eventos.get(2);
        assertEquals("CANCELADA", sinalizacao.getString("statusAnterior"));
        assertEquals("CANCELADA", sinalizacao.getString("statusNovo"));
        assertEquals("SISTEMA", sinalizacao.get("ator", Document.class).getString("perfil"));
        assertNull(sinalizacao.get("ator", Document.class).get("id"));
        assertEquals(2, sinalizacao.getInteger("totalSinalizados"));
        assertTrue(consultaDoBanco(consulta).getList("eventosPendentes", Document.class).isEmpty(),
                "eventos transferidos para o histórico");

        // Fila: só os dois compatíveis, na ordem de chegada, com aviso VAGA_LIBERADA.
        List<Document> avisos = db.getCollection("notificacoes")
                .find(Filters.and(Filters.eq("consultaId", consulta), Filters.eq("tipo", "VAGA_LIBERADA")))
                .sort(Sorts.ascending("_id")).into(new ArrayList<>());
        assertEquals(List.of(PACIENTES.get(1), PACIENTES.get(2)), avisos.stream().map(a -> a.getObjectId("destinatarioId")).toList());
        for (ObjectId id : List.of(qualquerMedico, medicoDaVagaManha)) {
            Document fila = fila(id);
            assertEquals("SINALIZADA", fila.getString("status"));
            Document ultima = fila.get("ultimaSinalizacao", Document.class);
            assertEquals(consulta, ultima.getObjectId("consultaOrigemId"));
            assertEquals(MEDICO, ultima.getObjectId("medicoId"));
            assertEquals(Date.from(VAGA), ultima.getDate("dataHora"));
            Document aviso = avisos.stream().filter(a -> a.getObjectId("_id").equals(ultima.getObjectId("notificacaoId")))
                    .findFirst().orElseThrow();
            assertEquals(id, aviso.getObjectId("filaEsperaId"));
            assertEquals("AGENDADA", aviso.getString("status"));
            assertEquals("Dra. Fila Teste", aviso.get("dadosConsulta", Document.class).getString("medicoNome"));
            assertTrue(aviso.getString("mensagem").contains("26/10 às 10:00"), aviso.getString("mensagem"));
        }
        for (ObjectId id : naoCompativeis) assertEquals("AGUARDANDO", fila(id).getString("status"));
    }

    @Test void semInteressadosRegistraSinalizacaoComTotalZero() {
        ObjectId consulta = agendar(PACIENTES.get(0), MEDICO, VAGA);
        cancelar(consulta);
        List<Document> eventos = historico(consulta);
        assertEquals("SINALIZACAO_FILA", eventos.get(eventos.size() - 1).getString("evento"));
        assertEquals(0, eventos.get(eventos.size() - 1).getInteger("totalSinalizados"));
    }

    @Test void vagaQueJaPassouNaoSinalizaNinguem() {
        ObjectId consulta = agendar(PACIENTES.get(0), MEDICO, AGORA.minus(Duration.ofHours(2)));
        ObjectId interesse = interesse(PACIENTES.get(1), ESPECIALIDADE, null, "QUALQUER", 1, null, null, 90);
        cancelar(consulta);
        assertEquals("AGUARDANDO", fila(interesse).getString("status"));
        assertEquals(0, historico(consulta).get(2).getInteger("totalSinalizados"));
    }

    @Test void pacienteComDoisInteressesCompativeisRecebeUmAvisoSo() {
        ObjectId consulta = agendar(PACIENTES.get(0), MEDICO, VAGA);
        ObjectId primeiro = interesse(PACIENTES.get(1), ESPECIALIDADE, null, "QUALQUER", 1, null, null, 90);
        ObjectId segundo = interesse(PACIENTES.get(1), ESPECIALIDADE, MEDICO, "QUALQUER", 2, null, null, 90);
        cancelar(consulta);
        assertEquals("SINALIZADA", fila(primeiro).getString("status"));
        assertEquals("AGUARDANDO", fila(segundo).getString("status"));
        assertEquals(1, db.getCollection("notificacoes").countDocuments(Filters.eq("consultaId", consulta)));
        assertEquals(1, historico(consulta).get(2).getInteger("totalSinalizados"));
    }

    @Test void quemCancelouNaoEAvisadoDaPropriaVaga() {
        ObjectId consulta = agendar(PACIENTES.get(0), MEDICO, VAGA);
        ObjectId proprio = interesse(PACIENTES.get(0), ESPECIALIDADE, null, "QUALQUER", 1, null, null, 90);
        cancelar(consulta);
        assertEquals("AGUARDANDO", fila(proprio).getString("status"));
    }

    // ---- apoio ----

    private ObjectId agendar(ObjectId paciente, ObjectId medico, Instant inicio) {
        return registro.criar(new Document("pacienteId", paciente).append("medicoId", medico)
                .append("especialidadeId", ESPECIALIDADE).append("inicio", Date.from(inicio))
                .append("fim", Date.from(inicio.plus(Duration.ofMinutes(30)))), SECRETARIA).getObjectId("_id");
    }

    private Document cancelar(ObjectId consulta) {
        return registro.alterar(consulta, new Document("status", "CANCELADA")
                .append("motivoCancelamento", "Teste da fila"), SECRETARIA);
    }

    /** Interesse AGUARDANDO; ordem define criadoEm (minutos antes de AGORA, crescente). */
    private ObjectId interesse(ObjectId paciente, ObjectId especialidade, ObjectId medico, String turno, int ordem,
                               Instant dataInicio, Instant dataFim, int validoPorDias) {
        Date criadoEm = Date.from(AGORA.minus(Duration.ofDays(10)).plus(Duration.ofMinutes(ordem)));
        Document d = new Document("_id", new ObjectId()).append("pacienteId", paciente)
                .append("especialidadeId", especialidade).append("medicoId", medico)
                .append("turnoPreferido", turno).append("status", "AGUARDANDO")
                .append("ultimaSinalizacao", null).append("consultaAgendadaId", null).append("atendidaEm", null)
                .append("validoAte", Date.from(AGORA.minus(Duration.ofDays(10)).plus(Duration.ofDays(validoPorDias))))
                .append("registradoPor", new Document("perfil", "PACIENTE").append("id", new ObjectId()))
                .append("criadoEm", criadoEm).append("atualizadoEm", criadoEm);
        if (dataInicio != null) d.append("dataInicio", Date.from(dataInicio));
        if (dataFim != null) d.append("dataFim", Date.from(dataFim));
        db.getCollection("filaEspera").insertOne(d);
        return d.getObjectId("_id");
    }

    private List<Document> historico(ObjectId consulta) {
        return db.getCollection("historico").find(Filters.eq("consultaId", consulta))
                .sort(Sorts.ascending("sequencia")).into(new ArrayList<>());
    }

    private Document fila(ObjectId id) { return db.getCollection("filaEspera").find(Filters.eq("_id", id)).first(); }

    private Document consultaDoBanco(ObjectId id) { return db.getCollection("consultas").find(Filters.eq("_id", id)).first(); }

    private void salvar(String colecao, Document d) {
        db.getCollection(colecao).replaceOne(Filters.eq("_id", d.get("_id")), d, new ReplaceOptions().upsert(true));
    }
}
