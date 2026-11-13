package br.edu.puccampinas.consultafacil.api.dto;

/** Corpo opcional de POST /consultas/{id}/cancelamento (#27). motivo: até 255 caracteres, sem dados clínicos. */
public record CancelamentoRequest(String motivo) {}
