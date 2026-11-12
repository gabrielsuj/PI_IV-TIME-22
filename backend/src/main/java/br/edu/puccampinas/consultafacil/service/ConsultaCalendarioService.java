package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.ConsultaCalendarioResponse;
import br.edu.puccampinas.consultafacil.repository.AgendaRepository;
import br.edu.puccampinas.consultafacil.repository.ConsultaRepository;
import br.edu.puccampinas.consultafacil.repository.MedicoRepository;
import br.edu.puccampinas.consultafacil.repository.NomesRepository;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Leitura das consultas de um médico num período, para o calendário operacional (#23).
 * Só leitura: criação, confirmação e cancelamento ficam com o fluxo de agendamento (#21, #27).
 */
public class ConsultaCalendarioService {
    /** Maior período aceito numa chamada (uma visão mensal com folga). */
    public static final int MAXIMO_DIAS = 62;

    private final ConsultaRepository consultas;
    private final AgendaRepository agendas;
    private final MedicoRepository medicos;
    private final NomesRepository nomes;

    public ConsultaCalendarioService(ConsultaRepository consultas, AgendaRepository agendas,
                                     MedicoRepository medicos, NomesRepository nomes) {
        this.consultas = consultas;
        this.agendas = agendas;
        this.medicos = medicos;
        this.nomes = nomes;
    }

    /** de e ate no formato AAAA-MM-DD, inclusivos, no fuso da agenda do médico. */
    public List<ConsultaCalendarioResponse> listar(ObjectId medicoId, String de, String ate) {
        LocalDate inicio = data(de, "de");
        LocalDate fim = data(ate, "ate");
        if (fim.isBefore(inicio)) throw ApiException.badRequest("ate deve ser igual ou posterior a de");
        if (ChronoUnit.DAYS.between(inicio, fim) + 1 > MAXIMO_DIAS)
            throw ApiException.badRequest("Período máximo de " + MAXIMO_DIAS + " dias");
        if (!medicos.existeAtivo(medicoId)) throw ApiException.notFound("Médico não encontrado ou inativo");

        ZoneId fuso = agendas.buscarPorMedico(medicoId)
                .map(a -> ZoneId.of(a.getString("fusoHorario")))
                .orElse(ZoneId.of(AgendaService.FUSO_PADRAO));
        List<Document> docs = consultas.doMedicoNoPeriodo(medicoId,
                Date.from(inicio.atStartOfDay(fuso).toInstant()),
                Date.from(fim.plusDays(1).atStartOfDay(fuso).toInstant()));

        Map<ObjectId, String> pacientes = nomes.pacientes(ids(docs, "pacienteId"));
        Map<ObjectId, String> especialidades = nomes.especialidades(ids(docs, "especialidadeId"));
        return docs.stream().map(d -> new ConsultaCalendarioResponse(
                d.getObjectId("_id").toHexString(),
                medicoId.toHexString(),
                d.getObjectId("pacienteId").toHexString(),
                pacientes.get(d.getObjectId("pacienteId")),
                d.getObjectId("especialidadeId").toHexString(),
                especialidades.get(d.getObjectId("especialidadeId")),
                local(d.getDate("inicio"), fuso),
                local(d.getDate("fim"), fuso),
                d.getString("status"))).toList();
    }

    private static Set<ObjectId> ids(List<Document> docs, String campo) {
        return docs.stream().map(d -> d.getObjectId(campo)).collect(Collectors.toSet());
    }

    private static String local(Date instante, ZoneId fuso) {
        return LocalDateTime.ofInstant(instante.toInstant(), fuso).toString();
    }

    private static LocalDate data(String valor, String campo) {
        if (valor == null || valor.isBlank()) throw ApiException.badRequest("Parâmetro obrigatório: " + campo);
        try {
            return LocalDate.parse(valor);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(campo + " deve estar no formato AAAA-MM-DD");
        }
    }
}
