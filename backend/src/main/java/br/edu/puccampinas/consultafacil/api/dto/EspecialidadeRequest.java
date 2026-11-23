/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

package br.edu.puccampinas.consultafacil.api.dto;

// O Boolean preserva a ausência do campo ativa para que a validação não a confunda com
// uma desativação explícita.
public record EspecialidadeRequest(String codigo, String nome, Boolean ativa) {}
