/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

package br.edu.puccampinas.consultafacil.api.dto;

import java.util.List;

// O corpo recebe dados profissionais; o vínculo de login existente é preservado pela
// atualização do cadastro.
public record MedicoRequest(String nome, String crmNumero, String crmUf,
                            List<String> especialidadeIds, Boolean ativo) {}
