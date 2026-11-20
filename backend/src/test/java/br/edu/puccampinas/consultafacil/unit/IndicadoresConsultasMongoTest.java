package br.edu.puccampinas.consultafacil.unit;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.repository.IndicadoresMongoRepository;
import br.edu.puccampinas.consultafacil.support.FontesIndicadoresEmMemoria;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.client.*;
import org.bson.*;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;

import static br.edu.puccampinas.consultafacil.support.FontesIndicadoresEmMemoria.*;
import static org.junit.jupiter.api.Assertions.*;

/** Contratos de consultas/projeções do driver. O dublê registra BSON; não executa nem interpreta filtros. */
class IndicadoresConsultasMongoTest {
    @Test void filtrosProtegemEscopoDatasStatusEProjecoesSemUsarTimelineTruncada() {
        var db = new DriverLeituras(fixture());
        var resultado = new IndicadoresMongoRepository(db.banco()).ler(PERIODO, Optional.of(MEDICO));
        assertEquals(4, resultado.confirmacao().registradas());
        assertEquals(new BsonDocument("_id", new BsonObjectId(MEDICO)), db.leituras.getFirst().filtro);
        var consultas = db.daColecao("consultas");
        assertTrue(clausulas(consultas.getFirst().filtro).contains(new BsonDocument("medicoId", new BsonObjectId(MEDICO))));
        assertTrue(clausulas(consultas.getFirst().filtro).contains(condicao("inicio", "$gte", PERIODO.inicioInclusivo())));
        assertTrue(clausulas(consultas.getFirst().filtro).contains(condicao("inicio", "$lt", PERIODO.fimExclusivo())));
        assertEquals(Set.of("_id", "medicoId", "inicio", "status", "eventosPendentes._id", "versao"), consultas.getFirst().projecao.keySet());
        var ocupantes = clausulas(consultas.get(1).filtro);
        assertTrue(ocupantes.contains(BsonDocument.parse("{status: {$ne: 'CANCELADA'}}")));
        assertTrue(ocupantes.contains(condicao("inicio", "$lt", Instant.parse("2026-11-23T16:00:00Z"))));
        assertTrue(ocupantes.contains(condicao("fim", "$gt", Instant.parse("2026-11-23T11:00:00Z"))));
        var historico = db.daColecao("historico").getFirst();
        assertEquals(Set.of("consultaId", "evento"), campos(historico.filtro));
        assertEquals(Set.of("consultaId", "evento", "_id"), historico.projecao.keySet());
        assertEquals(new BsonInt32(0), historico.projecao.get("_id"));
        var fila = db.daColecao("filaEspera").getFirst();
        assertEquals(Set.of("status", "consultaAgendadaId"), campos(fila.filtro));
        assertFalse(campos(fila.filtro).contains("medicoId"));
        assertFalse(campos(fila.filtro).contains("atendidaEm"));
    }

    @Test void lotesLimitamSomenteOTamanhoDoInSemPerderConsultasOuVinculos() {
        var fontes = new FontesIndicadoresEmMemoria();
        fontes.medicos.add(MEDICO);
        for (int i = 0; i < 501; i++) {
            ObjectId id = new ObjectId();
            fontes.consultas.add(new Document("_id", id).append("medicoId", MEDICO).append("status", "AGENDADA")
                    .append("inicio", Date.from(PERIODO.inicioInclusivo())).append("fim", Date.from(PERIODO.inicioInclusivo().plusSeconds(1800))));
            fontes.eventos.add(new Document("consultaId", id).append("evento", "CRIACAO"));
            fontes.fila.add(atendida(id));
        }
        var db = new DriverLeituras(fontes);
        var resultado = new IndicadoresMongoRepository(db.banco()).ler(PERIODO, Optional.of(MEDICO));
        assertEquals(501, resultado.consultas().agendadas());
        assertEquals(501, resultado.fila().preenchidas());
        for (String colecao : List.of("historico", "filaEspera")) {
            var lotes = db.daColecao(colecao);
            assertEquals(2, lotes.size());
            String chave = colecao.equals("historico") ? "consultaId" : "consultaAgendadaId";
            Set<BsonValue> ids = new HashSet<>();
            for (Leitura lote : lotes) for (BsonDocument clausula : clausulas(lote.filtro)) {
                if (!clausula.containsKey(chave)) continue;
                var valores = clausula.getDocument(chave).getArray("$in");
                assertTrue(valores.size() <= 500);
                ids.addAll(valores.getValues());
            }
            assertEquals(501, ids.size());
        }
    }

    @Test void filaGlobalNaoUsaDataDeAtendimentoEConsultaVinculadaForaDoRecorteEhBuscadaPorId() {
        var f = fixture();
        ObjectId fora = new ObjectId();
        f.fila.add(atendida(fora));
        var db = new DriverLeituras(f);
        new IndicadoresMongoRepository(db.banco()).ler(PERIODO, Optional.empty());
        assertEquals(BsonDocument.parse("{status: 'ATENDIDA'}"), db.daColecao("filaEspera").getFirst().filtro);
        var consultas = db.daColecao("consultas");
        assertEquals(3, consultas.size());
        assertEquals(new BsonArray(List.of(new BsonObjectId(fora))), consultas.getLast().filtro.getDocument("_id").getArray("$in"));
    }

    @Test void colecaoAusenteNaoEhConfundidaComBancoSemDados() {
        var db = new DriverLeituras(fixture());
        db.colecoes.remove("historico");
        assertEquals(503, assertThrows(ApiException.class, () -> new IndicadoresMongoRepository(db.banco()).ler(PERIODO, Optional.empty())).status());
        assertTrue(db.leituras.isEmpty());
    }

    @Test void falhaDoDriverNoMeioDaLeituraNaoRetornaResumoParcialComoSucesso() {
        var db = new DriverLeituras(fixture());
        db.falharNaColecao = "historico";
        assertEquals(503, assertThrows(ApiException.class, () -> new IndicadoresMongoRepository(db.banco()).ler(PERIODO, Optional.of(MEDICO))).status());
    }

    private static BsonDocument condicao(String campo, String operador, Instant valor) {
        return new BsonDocument(campo, new BsonDocument(operador, new BsonDateTime(valor.toEpochMilli())));
    }
    private static List<BsonDocument> clausulas(BsonDocument filtro) {
        return filtro.containsKey("$and") ? filtro.getArray("$and").stream().flatMap(v -> clausulas(v.asDocument()).stream()).toList() : List.of(filtro);
    }
    private static Set<String> campos(BsonDocument filtro) {
        Set<String> campos = new HashSet<>();
        clausulas(filtro).forEach(c -> campos.addAll(c.keySet()));
        return campos;
    }
    private static final class Leitura {
        final String colecao;
        final BsonDocument filtro;
        BsonDocument projecao;
        Leitura(String colecao, BsonDocument filtro) { this.colecao = colecao; this.filtro = filtro; }
    }

    private static final class DriverLeituras {
        final Map<String, List<Document>> respostas = new HashMap<>();
        final Set<String> colecoes = new HashSet<>(Set.of("medicos", "consultas", "agendas", "bloqueios", "historico", "filaEspera"));
        final List<Leitura> leituras = new ArrayList<>();
        String falharNaColecao;
        DriverLeituras(FontesIndicadoresEmMemoria f) {
            respostas.put("medicos", f.medicos.stream().map(id -> new Document("_id", id)).toList());
            respostas.put("consultas", f.consultas); respostas.put("agendas", f.agendas); respostas.put("historico", f.eventos); respostas.put("filaEspera", f.fila);
            respostas.put("bloqueios", f.bloqueios.values().stream().flatMap(Collection::stream).toList());
        }
        List<Leitura> daColecao(String colecao) { return leituras.stream().filter(l -> l.colecao.equals(colecao)).toList(); }
        private static BsonDocument bson(Object valor) { return ((Bson) valor).toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry()); }

        @SuppressWarnings("unchecked") MongoDatabase banco() {
            return (MongoDatabase) Proxy.newProxyInstance(MongoDatabase.class.getClassLoader(), new Class<?>[]{MongoDatabase.class}, (p, metodo, args) -> {
                if (metodo.getName().equals("listCollectionNames")) return Proxy.newProxyInstance(ListCollectionNamesIterable.class.getClassLoader(), new Class<?>[]{ListCollectionNamesIterable.class}, (p2, m, a) -> {
                    if (m.getName().equals("into")) { ((Collection<String>) a[0]).addAll(colecoes); return a[0]; }
                    throw new AssertionError(m.getName());
                });
                if (!metodo.getName().equals("getCollection")) throw new AssertionError(metodo.getName());
                String colecao = (String) args[0];
                return Proxy.newProxyInstance(MongoCollection.class.getClassLoader(), new Class<?>[]{MongoCollection.class}, (p2, m, a) -> {
                    if (!m.getName().equals("find")) throw new AssertionError("Somente leitura sem limites: " + m.getName());
                    if (colecao.equals(falharNaColecao)) throw new MongoException("Falha simulada");
                    var leitura = new Leitura(colecao, bson(a[0])); leituras.add(leitura);
                    return Proxy.newProxyInstance(FindIterable.class.getClassLoader(), new Class<?>[]{FindIterable.class}, (p3, operacao, argumentos) -> {
                        if (operacao.getName().equals("projection")) { leitura.projecao = bson(argumentos[0]); return p3; }
                        if (operacao.getName().equals("into")) { ((Collection<Document>) argumentos[0]).addAll(respostas.getOrDefault(colecao, List.of())); return argumentos[0]; }
                        throw new AssertionError("Somente leitura sem limites: " + operacao.getName());
                    });
                });
            });
        }
    }
}
