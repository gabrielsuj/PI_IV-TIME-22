package br.edu.puccampinas.consultafacil.api.dto;

import java.util.List;

public record MedicoRequest(String nome, String crmNumero, String crmUf,
                            List<String> especialidadeIds, Boolean ativo) {}
