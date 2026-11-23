/*
Autoria: Murilo Moraes
RA: 25000073
*/
package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.dto.IndicadoresResponse;
import br.edu.puccampinas.consultafacil.api.dto.IndicadoresResponse.Situacao;
import br.edu.puccampinas.consultafacil.config.MongoConnection;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.repository.IndicadoresMongoRepository;
import br.edu.puccampinas.consultafacil.repository.MedicoDoUsuarioRepository;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import br.edu.puccampinas.consultafacil.service.AcessoAgenda;
import br.edu.puccampinas.consultafacil.service.IndicadoresService;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Indicadores conferidos com os dados de demonstração (#48).
 *
 * Calcula os indicadores pelo serviço real (o mesmo de GET /indicadores) e confere cada contagem com
 * uma contagem independente, feita aqui direto nas coleções. Também exige que a carga não deixe nenhuma
 * taxa "incompleta" (o painel mostraria o indicador sem percentual).
 *
 * Opt-in: defina DEMO_MONGO_DB com um banco que tenha init + seed (o CI cria consultafacil_demo).
 * Localmente: scripts/recriar-banco.sh consultafacil_demo e depois
 *   DEMO_MONGO_DB=consultafacil_demo ./mvnw test -Dtest=IndicadoresDemonstracaoIntegrationTest
 */
class IndicadoresDemonstracaoIntegrationTest {
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final UsuarioLogado SECRETARIA =
            new UsuarioLogado(new ObjectId().toHexString(), "Secretaria", Perfil.SECRETARIA_ADMIN);

    private static MongoConnection mongo;
    private static MongoDatabase db;
    private static IndicadoresService indicadores;
    private static LocalDate hoje;

    @BeforeAll
    static void conectar() {
        String banco = System.getenv("DEMO_MONGO_DB");
        assumeTrue(banco != null && !banco.isBlank(), "Defina DEMO_MONGO_DB com o banco da carga de demonstração");
        mongo = new MongoConnection("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000", banco);
        db = mongo.database();
        assumeTrue(db.getCollection("consultas").countDocuments() > 0, "O banco " + banco + " não tem a carga de demonstração");
        indicadores = new IndicadoresService(new IndicadoresMongoRepository(db), new AcessoAgenda(new MedicoDoUsuarioRepository(db)));
        // Dia da carga: as datas do seed são relativas a ele (3 semanas antes e 3 depois).
        hoje = db.getCollection("consultas").find().sort(new Document("criadaEm", -1)).first()
                .getDate("criadaEm").toInstant().atZone(FUSO).toLocalDate();
    }

    @AfterAll
    static void fechar() { if (mongo != null) mongo.close(); }

    // ------------------------------------------------------------------ clínica inteira

    @Test
    void ultimasTresSemanasDaClinicaConferemComAsColecoes() {
        conferir(hoje.minusDays(21), hoje.minusDays(1), null);
    }

    @Test
    void proximasTresSemanasDaClinicaConferemComAsColecoes() {
        conferir(hoje, hoje.plusDays(21), null);
    }

    @Test
    void cadaMedicoComAgendaConfereNoPeriodoInteiroDaCarga() {
        for (ObjectId medico : db.getCollection("agendas").distinct("medicoId", ObjectId.class))
            conferir(hoje.minusDays(21), hoje.plusDays(21), medico);
    }

    @Test
    void nenhumaTaxaDaCargaFicaIncompletaOuPendente() {
        for (LocalDate[] periodo : List.of(new LocalDate[]{hoje.minusDays(21), hoje.minusDays(1)},
                new LocalDate[]{hoje, hoje.plusDays(21)})) {
            IndicadoresResponse r = indicadores.consultar(periodo[0].toString(), periodo[1].toString(), null, SECRETARIA);
            var taxas = Map.of("ocupação", r.ocupacao(), "faltas", r.faltas(), "cancelamento", r.cancelamento(),
                    "confirmação", r.confirmacao(), "preenchimento pela fila", r.preenchimentoFila());
            taxas.forEach((nome, taxa) -> assertTrue(taxa.situacao() == Situacao.DISPONIVEL || taxa.situacao() == Situacao.SEM_BASE,
                    nome + " ficou " + taxa.situacao() + " em " + periodo[0] + ".." + periodo[1]
                            + " (se a carga for antiga, recrie o banco com scripts/recriar-banco.sh consultafacil_demo)"));
            assertEquals(0, r.qualidade().medicosSemAgenda(), "médico inativo sem consultas não conta como 'sem agenda'");
            assertEquals(0, r.qualidade().consultasSemBaseHistorica(), "toda consulta concluída passou por CONFIRMACAO");
            assertEquals(0, r.qualidade().consultasComProjecaoPendente());
            assertEquals(0, r.qualidade().inscricoesComVinculoInvalido());
        }
    }

    // ------------------------------------------------------------------ conferência independente

    private static void conferir(LocalDate de, LocalDate ate, ObjectId medico) {
        String onde = (medico == null ? "clínica" : "médico " + medico) + " em " + de + ".." + ate;
        IndicadoresResponse r = indicadores.consultar(de.toString(), ate.toString(),
                medico == null ? null : medico.toHexString(), SECRETARIA);

        // Coorte: consultas que começam no período (dias de São Paulo), como em PeriodoIndicadores.
        var filtro = new ArrayList<>(List.of(
                Filters.gte("inicio", Date.from(de.atStartOfDay(FUSO).toInstant())),
                Filters.lt("inicio", Date.from(ate.plusDays(1).atStartOfDay(FUSO).toInstant()))));
        if (medico != null) filtro.add(Filters.eq("medicoId", medico));
        List<Document> coorte = db.getCollection("consultas").find(Filters.and(filtro)).into(new ArrayList<>());

        Map<String, Long> porStatus = new HashMap<>();
        coorte.forEach(c -> porStatus.merge(c.getString("status"), 1L, Long::sum));
        long canceladas = porStatus.getOrDefault("CANCELADA", 0L);
        long concluidas = porStatus.getOrDefault("CONCLUIDA", 0L), faltas = porStatus.getOrDefault("FALTA", 0L);
        Set<ObjectId> ids = new HashSet<>();
        coorte.forEach(c -> ids.add(c.getObjectId("_id")));
        Set<ObjectId> naoCanceladas = new HashSet<>();
        coorte.stream().filter(c -> !"CANCELADA".equals(c.getString("status"))).forEach(c -> naoCanceladas.add(c.getObjectId("_id")));

        long comConfirmacao = ids.isEmpty() ? 0 : db.getCollection("historico")
                .distinct("consultaId", Filters.and(Filters.eq("evento", "CONFIRMACAO"), Filters.in("consultaId", ids)), ObjectId.class)
                .into(new HashSet<>()).size();
        long pelaFila = db.getCollection("filaEspera")
                .distinct("consultaAgendadaId", Filters.eq("status", "ATENDIDA"), ObjectId.class)
                .into(new HashSet<>()).stream().filter(naoCanceladas::contains).count();

        var c = r.contagens();
        assertEquals(coorte.size(), c.totalConsultas(), "total de consultas, " + onde);
        assertEquals(porStatus.getOrDefault("AGENDADA", 0L), c.agendadas(), "agendadas, " + onde);
        assertEquals(porStatus.getOrDefault("CONFIRMADA", 0L), c.confirmadas(), "confirmadas, " + onde);
        assertEquals(concluidas, c.concluidas(), "concluídas, " + onde);
        assertEquals(faltas, c.faltas(), "faltas, " + onde);
        assertEquals(canceladas, c.canceladas(), "canceladas, " + onde);
        assertEquals(comConfirmacao, c.comConfirmacaoRegistrada(), "confirmações no histórico, " + onde);
        assertEquals(pelaFila, c.preenchidasPelaFila(), "reservas feitas pela fila, " + onde);
        // A carga só tem consultas dentro da jornada, sem bloqueio e sem sobreposição (scripts/verificar-carga.js):
        // cada consulta mantida ocupa exatamente uma vaga.
        assertEquals(naoCanceladas.size(), c.vagasOcupadas(), "vagas ocupadas, " + onde);
        assertTrue(c.capacidade() >= c.vagasOcupadas(), "capacidade >= ocupação, " + onde);

        // Fórmulas (numerador/denominador) com as contagens independentes.
        assertEquals(List.of(faltas, concluidas + faltas), List.of(r.faltas().numerador(), r.faltas().denominador()), "faltas, " + onde);
        assertEquals(List.of(canceladas, (long) coorte.size()),
                List.of(r.cancelamento().numerador(), r.cancelamento().denominador()), "cancelamento, " + onde);
        assertEquals(List.of(comConfirmacao, (long) coorte.size()),
                List.of(r.confirmacao().numerador(), r.confirmacao().denominador()), "confirmação, " + onde);
        assertEquals(List.of(pelaFila, (long) naoCanceladas.size()),
                List.of(r.preenchimentoFila().numerador(), r.preenchimentoFila().denominador()), "fila, " + onde);
    }
}
