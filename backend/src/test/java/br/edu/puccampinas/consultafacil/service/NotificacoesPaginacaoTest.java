package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.config.AppConfig;
import br.edu.puccampinas.consultafacil.repository.*;
import br.edu.puccampinas.consultafacil.support.RelogioAjustavel;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NotificacoesPaginacaoTest {
    private static final MongoDatabase BANCO = (MongoDatabase) Proxy.newProxyInstance(
            MongoDatabase.class.getClassLoader(), new Class<?>[]{MongoDatabase.class}, (proxy, metodo, args) -> null);

    @Test void percorrePaginaJaNotificadaEEmpateDeHorarioSemDuplicarEComJanelaFixa() {
        var relogio = new RelogioAjustavel(Instant.parse("2026-11-22T12:00:00Z"));
        var dados = new ArrayList<Document>();
        var existentes = new HashSet<ObjectId>();
        var inseridas = new HashMap<ObjectId, Document>();
        for (int i = 0; i < 250; i++) {
            ObjectId id = new ObjectId(String.format("%024x", i + 1));
            dados.add(new Document("_id", id).append("inicio", Date.from(relogio.instant().plusSeconds(i < 150 ? 3600 : 7200)))
                    .append("medicoId", new ObjectId()).append("pacienteId", new ObjectId()).append("status", "AGENDADA"));
            if (i < 100) existentes.add(id);
        }
        var janelas = new ArrayList<List<Date>>();
        var consultas = new ConsultaRepository(BANCO) {
            @Override public List<Document> ativasComInicioEntre(Date de, Date ate, Date inicio, ObjectId id, int limite) {
                janelas.add(List.of(de, ate));
                relogio.avancar(Duration.ofMinutes(1));
                return dados.stream().filter(d -> d.getDate("inicio").after(de) && !d.getDate("inicio").after(ate))
                        .filter(d -> inicio == null || d.getDate("inicio").after(inicio)
                                || d.getDate("inicio").equals(inicio) && d.getObjectId("_id").compareTo(id) > 0)
                        .limit(limite).toList();
            }
        };
        var notificacoes = new NotificacaoRepository(BANCO) {
            @Override public boolean temLembrete(ObjectId id) { return existentes.contains(id); }
            @Override public boolean inserirSeNova(Document n) {
                ObjectId id = n.getObjectId("consultaId");
                if (!existentes.add(id)) return false;
                inseridas.put(id, n);
                return true;
            }
        };
        var nomes = new NomesRepository() {
            public Map<ObjectId, String> pacientes(Collection<ObjectId> ids) { return Map.of(); }
            public Map<ObjectId, String> especialidades(Collection<ObjectId> ids) { return Map.of(); }
        };
        var service = new NotificacoesService(notificacoes, consultas, nomes, n -> {},
                new AppConfig.Notificacoes(24, 60, false), relogio);
        assertEquals(150, service.gerarLembretesDaJanela());
        assertEquals(4, janelas.size());
        assertTrue(janelas.stream().allMatch(janelas.getFirst()::equals));
        assertEquals(250, existentes.size());
        Date inicioCiclo = Date.from(Instant.parse("2026-11-22T12:00:00Z"));
        assertTrue(inseridas.values().stream().allMatch(n -> inicioCiclo.equals(n.getDate("agendadaPara"))));
        assertEquals(0, service.gerarLembretesDaJanela());
        assertEquals(150, inseridas.size());
    }
}
