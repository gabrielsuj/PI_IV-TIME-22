package br.edu.puccampinas.consultafacil.api.dto;

/**
 * Corpo de POST /consultas (#21).
 * inicio e fim: exatamente como vieram de GET /vagas (ISO 8601 com fuso, ex.: 2026-11-16T11:00:00Z).
 * pacienteId: obrigatório para a secretaria; o paciente logado pode omitir (a API usa o cadastro dele).
 */
public record AgendamentoRequest(String pacienteId, String medicoId, String especialidadeId,
                                 String inicio, String fim) {}
