package br.edu.puccampinas.consultafacil.api.dto;

import java.util.List;

/**
 * Página em sequência crescente, após aposSequencia (exclusivo).
 * limiteAtingido mantém a indicação de página cheia; temMais exige encontrar outro evento projetado.
 * proximaSequencia vem somente quando temMais é verdadeiro. Não representa pendências ainda não projetadas.
 */
public record HistoricoConsultaResponse(String consultaId, List<HistoricoEventoResponse> eventos,
                                       int aposSequencia, int limite, boolean limiteAtingido,
                                       boolean temMais, Integer proximaSequencia) {
    public HistoricoConsultaResponse {
        eventos = List.copyOf(eventos);
    }
}
