package br.edu.puccampinas.consultafacil.api.dto;

/**
 * Corpo de POST /fila-espera (#31). Datas no formato AAAA-MM-DD (horário de São Paulo).
 * medicoId null = qualquer médico da especialidade; turnoPreferido null = QUALQUER;
 * pacienteId só é informado pela secretaria.
 */
public record FilaEsperaRequest(String pacienteId, String especialidadeId, String medicoId,
                                String turnoPreferido, String dataInicio, String dataFim) {}
