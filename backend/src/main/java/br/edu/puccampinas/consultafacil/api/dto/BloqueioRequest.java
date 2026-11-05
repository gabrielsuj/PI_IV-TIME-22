package br.edu.puccampinas.consultafacil.api.dto;

/** inicio e fim em data-hora local (AAAA-MM-DDTHH:MM), no fuso da agenda do médico. */
public record BloqueioRequest(String medicoId, String inicio, String fim, String motivo) {}
