package br.edu.puccampinas.consultafacil.api.dto;

import java.util.List;

/**
 * Resposta de GET /vagas (#21).
 * fusoHorario: fuso da agenda, usado pela interface para exibir os horários.
 * semAgenda: true quando o médico ainda não configurou a agenda (horarios vem vazio).
 */
public record VagasResponse(String medicoId, String fusoHorario, boolean semAgenda, List<VagaDto> horarios) {}
