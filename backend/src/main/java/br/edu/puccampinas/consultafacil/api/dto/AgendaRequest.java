package br.edu.puccampinas.consultafacil.api.dto;

import java.util.List;

public record AgendaRequest(Integer duracaoMinutos, String fusoHorario, List<JornadaDto> jornadas) {}
