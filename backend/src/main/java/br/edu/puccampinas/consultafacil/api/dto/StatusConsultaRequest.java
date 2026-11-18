package br.edu.puccampinas.consultafacil.api.dto;

import br.edu.puccampinas.consultafacil.domain.enums.StatusConsulta;

/** POST /consultas/{id}/status: somente CONFIRMADA, CONCLUIDA ou FALTA; ator vem da sessão. */
public record StatusConsultaRequest(StatusConsulta status) {}
