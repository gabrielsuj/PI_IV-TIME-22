/*
Autoria: Murilo Moraes
RA: 25000073
*/
package br.edu.puccampinas.consultafacil.security;

/** Objeto `senha` gravado em `usuarios`: hash e salt em Base64, mais os parâmetros usados para gerá-lo. */
public record SenhaHash(String hash, String salt, String algoritmo, int iteracoes) {}
