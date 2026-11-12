package br.edu.puccampinas.consultafacil.api.dto;

/** Consulta como aparece no calendário. inicio e fim em data-hora local (AAAA-MM-DDTHH:MM) no fuso da agenda. */
public record ConsultaCalendarioResponse(String id, String medicoId, String pacienteId, String pacienteNome,
                                         String especialidadeId, String especialidadeNome,
                                         String inicio, String fim, String status) {}
