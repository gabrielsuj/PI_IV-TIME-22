package br.edu.puccampinas.consultafacil.api.dto;

/**
 * Consulta na listagem e no detalhe (#30), já com os nomes para exibição.
 * inicio e fim em ISO 8601 UTC (a tela converte para o fuso da clínica).
 * motivoCancelamento só vem preenchido em consulta cancelada com motivo.
 * podeCancelar: o servidor já aplicou a regra de status e de prazo da #27; a tela só mostra ou esconde o botão.
 */
public record ConsultaDetalheResponse(String id,
                                      String pacienteId, String pacienteNome,
                                      String medicoId, String medicoNome,
                                      String especialidadeId, String especialidadeNome,
                                      String inicio, String fim, String status,
                                      String motivoCancelamento, boolean podeCancelar) {}
