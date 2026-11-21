/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

package br.edu.puccampinas.consultafacil.api.dto;

// O identificador textual permite referenciar a especialidade na interface sem expor
// tipos internos do MongoDB.
public record EspecialidadeResponse(String id, String codigo, String nome, boolean ativa) {}
