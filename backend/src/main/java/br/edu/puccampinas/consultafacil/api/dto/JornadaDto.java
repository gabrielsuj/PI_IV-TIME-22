package br.edu.puccampinas.consultafacil.api.dto;

import java.util.List;

public record JornadaDto(Integer diaSemana, List<FaixaDto> faixas) {}
