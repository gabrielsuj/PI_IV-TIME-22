package br.edu.puccampinas.consultafacil.api.dto;

/** Identidade registrada no evento; perfil histórico pode ser legado, e SISTEMA não possui ID. */
public record HistoricoAtorResponse(String perfil, String id) {}
