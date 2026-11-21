/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

package br.edu.puccampinas.consultafacil.support;

import br.edu.puccampinas.consultafacil.repository.IndicadoresMongoRepository.Fontes;
import br.edu.puccampinas.consultafacil.service.PeriodoIndicadores;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.*;

// As chamadas e janelas ficam registradas para conferir o recorte consultado e
// detectar leituras repetidas ou fora do período.
public class FontesIndicadoresEmMemoria implements Fontes {
    public static final ObjectId MEDICO = new ObjectId();
    public static final PeriodoIndicadores PERIODO = PeriodoIndicadores.de("2026-11-23", "2026-11-23");
    public final List<ObjectId> medicos = new ArrayList<>();
    public final List<Document> consultas = new ArrayList<>(), agendas = new ArrayList<>(), eventos = new ArrayList<>(), fila = new ArrayList<>();
    public final Map<ObjectId, List<Document>> bloqueios = new HashMap<>();
    public final List<String> chamadas = new ArrayList<>();
    public final List<Janela> janelas = new ArrayList<>();
    public record Janela(String fonte, ObjectId medico, Instant inicio, Instant fim) {}

    public static FontesIndicadoresEmMemoria fixture() {
        var f = new FontesIndicadoresEmMemoria();
        f.medicos.add(MEDICO);
        f.agendas.add(new Document("medicoId", MEDICO).append("fusoHorario", "America/Sao_Paulo").append("duracaoMinutos", 30)
                .append("jornadas", List.of(new Document("diaSemana", 1).append("faixas", List.of(new Document("inicioMinuto", 480).append("fimMinuto", 780))))));
        f.bloqueios.put(MEDICO, List.of(intervalo("2026-11-23T12:00:00Z", "2026-11-23T13:00:00Z")));
        String[][] linhas = {{"11:00", "11:30", "AGENDADA"}, {"11:30", "12:00", "CONFIRMADA"},
                {"13:00", "14:00", "CONCLUIDA"}, {"14:00", "14:30", "CONCLUIDA"}, {"14:30", "15:00", "FALTA"}, {"15:00", "15:30", "CANCELADA"}};
        for (String[] linha : linhas) {
            var c = intervalo("2026-11-23T" + linha[0] + ":00Z", "2026-11-23T" + linha[1] + ":00Z")
                    .append("_id", new ObjectId()).append("medicoId", MEDICO).append("status", linha[2]).append("eventosPendentes", List.of());
            f.consultas.add(c);
            f.eventos.add(new Document("consultaId", c.getObjectId("_id")).append("evento", "CRIACAO"));
            if (Set.of("CONFIRMADA", "CONCLUIDA", "CANCELADA").contains(linha[2]))
                f.eventos.add(new Document("consultaId", c.getObjectId("_id")).append("evento", "CONFIRMACAO"));
        }
        for (int indice : List.of(0, 2, 5)) f.fila.add(atendida(f.consultas.get(indice).getObjectId("_id")));
        return f;
    }

    public static Document intervalo(String inicio, String fim) {
        return new Document("inicio", Date.from(Instant.parse(inicio))).append("fim", Date.from(Instant.parse(fim)));
    }
    public static Document atendida(ObjectId consulta) {
        return new Document("_id", new ObjectId()).append("status", "ATENDIDA").append("consultaAgendadaId", consulta);
    }
    @Override public List<ObjectId> medicos(Optional<ObjectId> medico) { chamadas.add("medicos"); return medicos; }
    @Override public List<Document> consultas(PeriodoIndicadores periodo, Optional<ObjectId> medico) { chamadas.add("consultas"); return consultas; }
    @Override public List<Document> agendas(Set<ObjectId> ids) { chamadas.add("agendas"); return agendas; }
    @Override public List<Document> historico(Set<ObjectId> ids) { chamadas.add("historico"); return eventos; }
    @Override public List<Document> atendidas(Optional<ObjectId> medico, Set<ObjectId> ids) {
        chamadas.add("fila");
        return medico.isEmpty() ? fila : fila.stream().filter(d -> ids.contains(d.get("consultaAgendadaId"))).toList();
    }
    @Override public List<Document> consultasPorIds(Set<ObjectId> ids) {
        chamadas.add("vinculadas");
        return consultas.stream().filter(d -> ids.contains(d.getObjectId("_id"))).toList();
    }
    @Override public List<Document> bloqueios(ObjectId medico, Instant inicio, Instant fim) {
        janelas.add(new Janela("bloqueios", medico, inicio, fim));
        return bloqueios.getOrDefault(medico, List.of());
    }
    @Override public List<Document> ocupantes(ObjectId medico, Instant inicio, Instant fim) {
        janelas.add(new Janela("ocupantes", medico, inicio, fim));
        return consultas.stream().filter(d -> medico.equals(d.getObjectId("medicoId"))).toList();
    }
}
