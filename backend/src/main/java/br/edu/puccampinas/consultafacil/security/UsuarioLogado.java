package br.edu.puccampinas.consultafacil.security;

import br.edu.puccampinas.consultafacil.domain.enums.Perfil;

/** Quem fez a requisição. É o que a autorização por perfil (#10) consulta. */
public record UsuarioLogado(String id, String nome, Perfil perfil) {}
