package br.edu.puccampinas.consultafacil.service;

import org.bson.Document;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Cálculo de disponibilidade (#20): os horários livres de um médico em um dia.
 *
 * Regras (ver docs/modelagem/medicos-agenda.md):
 *  - os horários saem das faixas da jornada do dia da semana, de "duracaoMinutos" em "duracaoMinutos";
 *  - o horário só entra se couber inteiro na faixa;
 *  - sai da lista o horário que se sobrepõe a um bloqueio ou a uma consulta ativa
 *    (intervalos com início incluído e fim excluído, igual ao banco);
 *  - sai da lista o horário que começa antes de "agora".
 *
 * Esta classe não acessa o banco: recebe tudo pronto. Por isso é testada sem MongoDB
 * (CalculadoraDisponibilidadeTest). Quem busca os dados é o DisponibilidadeService.
 */
public final class CalculadoraDisponibilidade {
    private CalculadoraDisponibilidade() {}

    /** Um horário livre, em instantes UTC. */
    public record Vaga(Instant inicio, Instant fim) {}

    /**
     * @param agenda    documento de `agendas` (fusoHorario, duracaoMinutos, jornadas)
     * @param data      dia pedido, no fuso da agenda
     * @param bloqueios bloqueios do médico que tocam o dia (campos inicio e fim)
     * @param consultas consultas ATIVAS do médico que tocam o dia (campos inicio e fim)
     * @param agora     instante atual; horários que começam antes dele não são oferecidos
     */
    public static List<Vaga> calcular(Document agenda, LocalDate data,
                                      List<Document> bloqueios, List<Document> consultas, Instant agora) {
        ZoneId fuso = ZoneId.of(agenda.getString("fusoHorario"));
        int duracao = agenda.getInteger("duracaoMinutos");
        int diaSemana = data.getDayOfWeek().getValue();   // ISO 8601: 1 = segunda ... 7 = domingo

        List<Vaga> vagas = new ArrayList<>();
        for (Document jornada : agenda.getList("jornadas", Document.class)) {
            if (jornada.getInteger("diaSemana") != diaSemana) continue;   // dia sem atendimento

            for (Document faixa : jornada.getList("faixas", Document.class)) {
                int fimFaixa = faixa.getInteger("fimMinuto");

                // Ex.: faixa 480–720 (08h–12h) com 30 min → 480, 510, ..., 690 (o último termina às 12h).
                for (int minuto = faixa.getInteger("inicioMinuto"); minuto + duracao <= fimFaixa; minuto += duracao) {
                    // Minutos desde a meia-noite no fuso da agenda → instante UTC.
                    Instant inicio = data.atStartOfDay().plusMinutes(minuto).atZone(fuso).toInstant();
                    Instant fim = inicio.plusSeconds(duracao * 60L);

                    if (inicio.isBefore(agora)) continue;
                    if (sobrepoe(inicio, fim, bloqueios)) continue;
                    if (sobrepoe(inicio, fim, consultas)) continue;
                    vagas.add(new Vaga(inicio, fim));
                }
            }
        }
        vagas.sort(Comparator.comparing(Vaga::inicio));
        return vagas;
    }

    /**
     * Mesma condição de sobreposição usada nas buscas do banco:
     * [inicio, fim) toca [p.inicio, p.fim) quando inicio < p.fim e fim > p.inicio.
     * Assim, um bloqueio que termina às 10h não impede o horário que começa às 10h.
     */
    static boolean sobrepoe(Instant inicio, Instant fim, List<Document> periodos) {
        for (Document periodo : periodos) {
            Instant inicioPeriodo = periodo.getDate("inicio").toInstant();
            Instant fimPeriodo = periodo.getDate("fim").toInstant();
            if (inicio.isBefore(fimPeriodo) && fim.isAfter(inicioPeriodo)) return true;
        }
        return false;
    }
}
