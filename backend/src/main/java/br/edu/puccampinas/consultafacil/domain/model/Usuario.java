package br.edu.puccampinas.consultafacil.domain.model;

import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.security.SenhaHash;

/** Registro de `usuarios` usado no login. A senha nunca sai da API. */
public record Usuario(String id, String nome, String email, Perfil perfil, boolean ativo, SenhaHash senha) {}
