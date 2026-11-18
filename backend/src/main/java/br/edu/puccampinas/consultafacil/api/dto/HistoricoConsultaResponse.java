package br.edu.puccampinas.consultafacil.api.dto;

import java.util.List;

/** limiteAtingido informa somente que a leitura alcançou o teto, sem afirmar que existem mais eventos. */
public record HistoricoConsultaResponse(String consultaId, List<HistoricoEventoResponse> eventos,
                                       int limite, boolean limiteAtingido) {
    public HistoricoConsultaResponse {
        eventos = List.copyOf(eventos);
    }
}
