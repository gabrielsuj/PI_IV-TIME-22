/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

package br.edu.puccampinas.consultafacil.api.dto;

import java.util.List;

/**
 * Página em sequência crescente, após aposSequencia (exclusivo).
 * limiteAtingido mantém a indicação de página cheia; temMais exige encontrar outro evento projetado.
 * proximaSequencia vem somente quando temMais é verdadeiro. Não representa pendências ainda não projetadas.
 * atualizacaoPendente sinaliza pendências ou uma alteração observada durante a leitura; não expõe a caixa interna.
 */
public record HistoricoConsultaResponse(String consultaId, List<HistoricoEventoResponse> eventos,
                                       int aposSequencia, int limite, boolean limiteAtingido,
                                       boolean temMais, Integer proximaSequencia, boolean atualizacaoPendente) {
    public HistoricoConsultaResponse {
        // A cópia impede que mudanças posteriores na lista de origem alterem a página
        // já preparada para a resposta.
        eventos = List.copyOf(eventos);
    }
}
