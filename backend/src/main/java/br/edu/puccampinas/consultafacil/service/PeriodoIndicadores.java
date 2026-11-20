package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Período da #36: datas inclusivas no fuso da clínica, inclusive ao reunir médicos com agendas
 * em fusos diferentes. A leitura deve usar a janela UTC [inicioInclusivo, fimExclusivo).
 * O recorte das consultas considera seu início, não a data em que foram criadas ou alteradas.
 */
public record PeriodoIndicadores(LocalDate de, LocalDate ate) {
    public static final ZoneId FUSO = ZoneId.of(AgendaService.FUSO_PADRAO);
    /** Mesmo limite de dias da visão de calendário; não limita o número de consultas lidas. */
    public static final int MAXIMO_DIAS = 62;

    public PeriodoIndicadores {
        if (de == null || ate == null) throw ApiException.badRequest("Informe de e ate para consultar os indicadores");
        if (de.getYear() < 1 || de.getYear() > 9999 || ate.getYear() < 1 || ate.getYear() > 9999)
            throw ApiException.badRequest("As datas dos indicadores devem estar no formato AAAA-MM-DD");
        if (ate.isBefore(de)) throw ApiException.badRequest("ate deve ser igual ou posterior a de");
        if (ChronoUnit.DAYS.between(de, ate) + 1 > MAXIMO_DIAS)
            throw ApiException.badRequest("Período máximo de " + MAXIMO_DIAS + " dias para os indicadores");
    }

    public static PeriodoIndicadores de(String de, String ate) {
        return new PeriodoIndicadores(data(de, "de"), data(ate, "ate"));
    }

    public Instant inicioInclusivo() { return de.atStartOfDay(FUSO).toInstant(); }
    public Instant fimExclusivo() { return ate.plusDays(1).atStartOfDay(FUSO).toInstant(); }

    private static LocalDate data(String valor, String campo) {
        if (valor == null || valor.isBlank()) throw ApiException.badRequest("Parâmetro obrigatório: " + campo);
        if (!valor.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw ApiException.badRequest(campo + " deve estar no formato AAAA-MM-DD");
        try {
            return LocalDate.parse(valor);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(campo + " deve ser uma data válida no formato AAAA-MM-DD");
        }
    }
}
