/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

package br.edu.puccampinas.consultafacil.repository;

import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.List;

/**
 * Leitura interna dos eventos já projetados em historico (#46), sem escrita nem recuperação de pendências.
 * A autorização da consulta cabe ao serviço chamador; documentos Mongo não são respostas públicas da API.
 */
@FunctionalInterface
public interface HistoricoLeituraRepository {
    int LIMITE_MAXIMO = 100;

    /**
     * Eventos da consulta em sequência crescente, estritamente após aposSequencia (zero inicia a leitura).
     * Limite de 1 a 100; uma lista vazia não distingue consulta inexistente de ausência de eventos projetados.
     * Não inclui a caixa eventosPendentes da consulta e não altera o estado da projeção.
     */
    // O cursor avança pela sequência projetada; uma pendência de gravação continua
    // sendo informada pelo serviço sem virar evento desta leitura.
    List<Document> listarPorConsulta(ObjectId consultaId, int aposSequencia, int limite);
}
