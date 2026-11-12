package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.repository.AgendaRepository;
import br.edu.puccampinas.consultafacil.repository.BloqueioRepository;
import br.edu.puccampinas.consultafacil.repository.ConsultaRepository;
import br.edu.puccampinas.consultafacil.service.CalculadoraDisponibilidade.Vaga;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Disponibilidade de um médico (#20). Busca agenda, bloqueios e consultas ativas
 * e entrega para a CalculadoraDisponibilidade.
 *
 * Usado pela busca de vagas e pela confirmação do agendamento (#21) e pode ser usado
 * pelo calendário operacional (#23).
 * Não verifica se o médico está ativo: isso fica com quem chama.
 */
public class DisponibilidadeService {

    /** Resultado de um dia. semAgenda = o médico ainda não configurou a agenda. */
    public record Resultado(String fusoHorario, boolean semAgenda, List<Vaga> vagas) {}

    private final AgendaRepository agendas;
    private final BloqueioRepository bloqueios;
    private final ConsultaRepository consultas;
    private final Clock relogio;

    public DisponibilidadeService(AgendaRepository agendas, BloqueioRepository bloqueios,
                                  ConsultaRepository consultas, Clock relogio) {
        this.agendas = agendas;
        this.bloqueios = bloqueios;
        this.consultas = consultas;
        this.relogio = relogio;
    }

    /** Horários livres do médico na data (no fuso da agenda dele). */
    public Resultado doDia(ObjectId medicoId, LocalDate data) {
        Optional<Document> agenda = agendas.buscarPorMedico(medicoId);
        if (agenda.isEmpty()) return new Resultado(AgendaService.FUSO_PADRAO, true, List.of());

        ZoneId fuso = ZoneId.of(agenda.get().getString("fusoHorario"));
        // Janela do dia inteiro, da meia-noite até a meia-noite seguinte, no fuso da agenda.
        Date inicioDia = Date.from(data.atStartOfDay(fuso).toInstant());
        Date fimDia = Date.from(data.plusDays(1).atStartOfDay(fuso).toInstant());

        List<Vaga> vagas = CalculadoraDisponibilidade.calcular(
                agenda.get(),
                data,
                bloqueios.sobrepostos(medicoId, inicioDia, fimDia),
                consultas.ativasNoPeriodo(medicoId, inicioDia, fimDia),
                relogio.instant());
        return new Resultado(fuso.getId(), false, vagas);
    }

    /**
     * Revalidação na hora de confirmar (#21): o horário precisa ser exatamente uma das vagas livres.
     * Assim o mesmo cálculo garante jornada, duração, bloqueios, consultas e horário futuro.
     */
    public boolean estaLivre(ObjectId medicoId, Instant inicio, Instant fim) {
        Optional<Document> agenda = agendas.buscarPorMedico(medicoId);
        if (agenda.isEmpty()) return false;

        ZoneId fuso = ZoneId.of(agenda.get().getString("fusoHorario"));
        LocalDate data = LocalDate.ofInstant(inicio, fuso);
        return doDia(medicoId, data).vagas().contains(new Vaga(inicio, fim));
    }
}
