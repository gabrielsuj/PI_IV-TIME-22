package br.edu.puccampinas.consultafacil.api.dto;

/** Um horário livre. inicio e fim em ISO 8601 UTC (ex.: 2026-11-16T11:00:00Z). */
public record VagaDto(String inicio, String fim) {}
