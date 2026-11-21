/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

package br.edu.puccampinas.consultafacil.api.dto;

import br.edu.puccampinas.consultafacil.domain.enums.StatusConsulta;

/** POST /consultas/{id}/status: somente CONFIRMADA, CONCLUIDA ou FALTA; ator vem da sessão. */
// O cancelamento usa seu próprio endpoint; este corpo não recebe motivo nem
// identificação do responsável.
public record StatusConsultaRequest(StatusConsulta status) {}
