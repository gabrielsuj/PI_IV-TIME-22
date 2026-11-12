package br.edu.puccampinas.consultafacil.api.dto;

import java.util.List;

public record MedicoResponse(String id, String nome, String crmNumero, String crmUf,
                             List<String> especialidadeIds, boolean ativo) {}
