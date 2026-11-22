/*
Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

package br.edu.puccampinas.consultafacil.api.dto;

import java.util.List;

public record JornadaDto(Integer diaSemana, List<FaixaDto> faixas) {}
