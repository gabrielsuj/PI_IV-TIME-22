package br.edu.puccampinas.consultafacil.api.dto;

import java.util.List;

/** Resposta 409 quando uma mudança de agenda ou um bloqueio atinge consultas ativas (#44). */
public record ConflitoConsultasResponse(int status, String erro, List<ConsultaCalendarioResponse> consultas) {}
