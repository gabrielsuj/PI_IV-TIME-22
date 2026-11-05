package br.edu.puccampinas.consultafacil.api.dto;

import java.util.List;

public record AgendaResponse(String id, String medicoId, String fusoHorario,
                             int duracaoMinutos, List<JornadaDto> jornadas) {}
