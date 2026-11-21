/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

package br.edu.puccampinas.consultafacil.api.dto;

import java.util.List;

// As especialidades permanecem como IDs textuais para que o frontend resolva os
// rótulos a partir do catálogo.
public record MedicoResponse(String id, String nome, String crmNumero, String crmUf,
                             List<String> especialidadeIds, boolean ativo) {}
