package br.edu.puccampinas.consultafacil.api.dto;

import br.edu.puccampinas.consultafacil.security.UsuarioLogado;

/** expiraEm em ISO-8601 (UTC), ex.: "2026-11-12T15:00:00Z". */
public record LoginResponse(String token, String expiraEm, UsuarioLogado usuario) {}
