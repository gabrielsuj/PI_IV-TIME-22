package br.edu.puccampinas.consultafacil.api.dto;

/** Consulta devolvida pela API. inicio e fim em ISO 8601 UTC. */
public record ConsultaResponse(String id, String pacienteId, String medicoId, String especialidadeId,
                               String inicio, String fim, String status) {}
