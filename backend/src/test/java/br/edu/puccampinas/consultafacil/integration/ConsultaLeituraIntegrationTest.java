package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.ConsultaDetalheResponse;
import br.edu.puccampinas.consultafacil.config.MongoConnection;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.repository.*;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import br.edu.puccampinas.consultafacil.service.AcessoConsulta;
import br.edu.puccampinas.consultafacil.service.ConsultaLeituraService;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Listagem e detalhe de consultas (#30) com o MongoDB de verdade.
 * Requer o MongoDB local com database/init aplicado no consultafacil_test
 * (scripts/recriar-banco.sh consultafacil_test).
 *
 * Relógio parado em 20/10/2026 12:00Z. Paulo tem 2 consultas futuras e 1 passada; Carla tem 1 futura cancelada.
 */
class ConsultaLeituraIntegrationTest {
    private static final Instant AGORA = Instant.parse("2026-10-20T12:00:00Z");

    private static final ObjectId ESPECIALIDADE = new ObjectId("d00000000000000000000001");
    private static final ObjectId DRA_ANA = new ObjectId("d10000000000000000000001");
    private static final ObjectId DR_BRUNO = new ObjectId("d10000000000000000000002");
    private static final ObjectId PAULO = new ObjectId("d40000000000000000000001");
    private static final ObjectId CARLA = new ObjectId("d40000000000000000000002");

    private static final UsuarioLogado PAULO_LOGADO = new UsuarioLogado("d60000000000000000000001", "Paulo", Perfil.PACIENTE);
    private static final UsuarioLogado CARLA_LOGADA = new UsuarioLogado("d60000000000000000000002", "Carla", Perfil.PACIENTE);
    private static final UsuarioLogado SEM_CADASTRO = new UsuarioLogado("d60000000000000000000009", "Sem cadastro", Perfil.PACIENTE);
    private static final UsuarioLogado ANA_LOGADA = new UsuarioLogado("d70000000000000000000001", "Ana", Perfil.MEDICO);
    private static final UsuarioLogado SECRETARIA = new UsuarioLogado(new ObjectId().toHexString(), "Sofia", Perfil.SECRETARIA_ADMIN);

    // Paulo
    private static final ObjectId PAULO_ANA_AMANHA = new ObjectId("d50000000000000000000001");
    private static final ObjectId PAULO_BRUNO_SEMANA = new ObjectId("d50000000000000000000002");
    private static final ObjectId PAULO_ANA_PASSADA = new ObjectId("d50000000000000000000003");
    // Carla
    private static final ObjectId CARLA_ANA_CANCELADA = new ObjectId("d50000000000000000000004");

    private static MongoConnection mongo;
    private MongoDatabase db;
    private ConsultaLeituraService service;

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

        salvar("especialidades", new Document("_id", ESPECIALIDADE)
                .append("codigo", "teste-leitura-1").append("nome", "Clínica Teste Leitura").append("ativa", true));
        salvar("medicos", medico(DRA_ANA, "Ana Teste Leitura", "9900401", "d70000000000000000000001"));
        salvar("medicos", medico(DR_BRUNO, "Bruno Teste Leitura", "9900402", null));
        salvar("pacientes", new Document("_id", PAULO).append("nome", "Paulo Teste Leitura")
                .append("cpf", "61384029796").append("email", "leitura.teste1@consultafacil.test")
                .append("usuarioId", new ObjectId(PAULO_LOGADO.id())));
        salvar("pacientes", new Document("_id", CARLA).append("nome", "Carla Teste Leitura")
                .append("cpf", "72810593612").append("email", "leitura.teste2@consultafacil.test")
                .append("usuarioId", new ObjectId(CARLA_LOGADA.id())));

        var consultas = db.getCollection("consultas");
        consultas.insertOne(consulta(PAULO_ANA_AMANHA, PAULO, DRA_ANA, AGORA.plus(Duration.ofDays(1)), "AGENDADA"));
        consultas.insertOne(consulta(PAULO_BRUNO_SEMANA, PAULO, DR_BRUNO, AGORA.plus(Duration.ofDays(7)), "CONFIRMADA"));
        consultas.insertOne(consulta(PAULO_ANA_PASSADA, PAULO, DRA_ANA, AGORA.minus(Duration.ofDays(5)), "CONCLUIDA"));
        consultas.insertOne(consulta(CARLA_ANA_CANCELADA, CARLA, DRA_ANA, AGORA.plus(Duration.ofDays(2)), "CANCELADA")
                .append("motivoCancelamento", "Viagem"));

        var acesso = new AcessoConsulta(new PacienteDoUsuarioRepository(db)::buscar, new MedicoDoUsuarioRepository(db)::buscar);
        service = new ConsultaLeituraService(new ConsultaRepository(db), new NomesRepository.Mongo(db), acesso,
                Clock.fixed(AGORA, ZoneOffset.UTC));
    }

    private void salvar(String colecao, Document d) {
        db.getCollection(colecao).replaceOne(Filters.eq("_id", d.get("_id")), d, new ReplaceOptions().upsert(true));
    }

    private static Document medico(ObjectId id, String nome, String crm, String usuarioId) {
        Document d = new Document("_id", id).append("nome", nome).append("crmNumero", crm).append("crmUf", "AC")
                .append("especialidadeIds", List.of(ESPECIALIDADE)).append("ativo", true);
        if (usuarioId != null) d.append("usuarioId", new ObjectId(usuarioId));
        return d;
    }

    private static Document consulta(ObjectId id, ObjectId paciente, ObjectId medico, Instant inicio, String status) {
        return new Document("_id", id).append("pacienteId", paciente).append("medicoId", medico)
                .append("especialidadeId", ESPECIALIDADE)
                .append("inicio", Date.from(inicio)).append("fim", Date.from(inicio.plus(Duration.ofMinutes(30))))
                .append("status", status).append("criadaEm", Date.from(AGORA.minus(Duration.ofDays(10))));
    }

    private static List<String> ids(List<ConsultaDetalheResponse> lista) {
        return lista.stream().map(ConsultaDetalheResponse::id).toList();
    }

    private static int status(Runnable acao) {
        return assertThrows(ApiException.class, acao::run).status();
    }

    // ------------------------------------------------------------------ listagem

    @Test
    void pacienteVeSoAsProprias_proximasDaMaisPertoParaAMaisLonge() {
        List<ConsultaDetalheResponse> lista = service.listar(null, null, null, PAULO_LOGADO);

        assertEquals(List.of(PAULO_ANA_AMANHA.toHexString(), PAULO_BRUNO_SEMANA.toHexString()), ids(lista));
        ConsultaDetalheResponse primeira = lista.get(0);
        assertEquals("Paulo Teste Leitura", primeira.pacienteNome());
        assertEquals("Ana Teste Leitura", primeira.medicoNome());
        assertEquals("Clínica Teste Leitura", primeira.especialidadeNome());
        assertEquals("2026-10-21T12:00:00Z", primeira.inicio());
        assertTrue(primeira.podeCancelar());
    }

    @Test
    void anterioresVemDaMaisRecenteParaAMaisAntigaENaoPodemSerCanceladas() {
        List<ConsultaDetalheResponse> lista = service.listar("anteriores", null, null, PAULO_LOGADO);

        assertEquals(List.of(PAULO_ANA_PASSADA.toHexString()), ids(lista));
        assertFalse(lista.get(0).podeCancelar());
    }

    @Test
    void filtraPorStatusEPorMedico() {
        assertEquals(List.of(PAULO_BRUNO_SEMANA.toHexString()), ids(service.listar("proximas", "CONFIRMADA", null, PAULO_LOGADO)));
        assertEquals(List.of(PAULO_ANA_AMANHA.toHexString()),
                ids(service.listar("proximas", null, DRA_ANA.toHexString(), PAULO_LOGADO)));
    }

    @Test
    void medicoVeAsConsultasQueAtendeDeTodosOsPacientes() {
        List<ConsultaDetalheResponse> lista = service.listar(null, null, null, ANA_LOGADA);

        assertEquals(List.of(PAULO_ANA_AMANHA.toHexString(), CARLA_ANA_CANCELADA.toHexString()), ids(lista));
        assertEquals("Carla Teste Leitura", lista.get(1).pacienteNome());
        assertFalse(lista.get(1).podeCancelar(), "cancelada não pode ser cancelada de novo");
    }

    @Test
    void medicoNaoVeConsultasDeOutroMedicoMesmoFiltrando() {
        assertTrue(service.listar(null, null, DR_BRUNO.toHexString(), ANA_LOGADA).isEmpty());
    }

    @Test
    void secretariaVeTodasEPodeFiltrarPorMedico() {
        assertEquals(3, service.listar(null, null, null, SECRETARIA).size());
        assertEquals(List.of(PAULO_BRUNO_SEMANA.toHexString()),
                ids(service.listar(null, null, DR_BRUNO.toHexString(), SECRETARIA)));
    }

    @Test
    void filtrosInvalidosRecebem400() {
        assertEquals(400, status(() -> service.listar("amanha", null, null, SECRETARIA)));
        assertEquals(400, status(() -> service.listar(null, "PENDENTE", null, SECRETARIA)));
        assertEquals(400, status(() -> service.listar(null, null, "abc", SECRETARIA)));
    }

    @Test
    void loginSemCadastroDePacienteRecebe403() {
        assertEquals(403, status(() -> service.listar(null, null, null, SEM_CADASTRO)));
    }

    // ------------------------------------------------------------------ detalhe

    @Test
    void detalheTrazNomesEMotivoDoCancelamento() {
        ConsultaDetalheResponse d = service.detalhar(CARLA_ANA_CANCELADA, CARLA_LOGADA);

        assertEquals("CANCELADA", d.status());
        assertEquals("Viagem", d.motivoCancelamento());
        assertEquals("Ana Teste Leitura", d.medicoNome());
        assertFalse(d.podeCancelar());
    }

    @Test
    void pacienteNaoAbreConsultaDeOutroPaciente() {
        assertEquals(403, status(() -> service.detalhar(CARLA_ANA_CANCELADA, PAULO_LOGADO)));
    }

    @Test
    void medicoAbreAsDeleENaoAsDeOutroMedico() {
        assertEquals("Paulo Teste Leitura", service.detalhar(PAULO_ANA_AMANHA, ANA_LOGADA).pacienteNome());
        assertEquals(403, status(() -> service.detalhar(PAULO_BRUNO_SEMANA, ANA_LOGADA)));
    }

    @Test
    void consultaInexistenteRecebe404() {
        assertEquals(404, status(() -> service.detalhar(new ObjectId(), SECRETARIA)));
    }
}
