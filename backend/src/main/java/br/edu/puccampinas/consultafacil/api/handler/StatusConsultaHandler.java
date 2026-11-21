/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

package br.edu.puccampinas.consultafacil.api.handler;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.api.dto.StatusConsultaRequest;
import br.edu.puccampinas.consultafacil.security.Autorizacao;
import br.edu.puccampinas.consultafacil.service.StatusConsultaService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * POST /consultas/{id}/status (#28), corpo: {"status":"CONFIRMADA|CONCLUIDA|FALTA"}.
 * 200 com ConsultaResponse; 400 JSON/ID/status inválido; 401 sem sessão; 403 sem permissão/vínculo;
 * 404 consulta inexistente; 409 transição, prazo ou versão; 503 limite de histórico pendente.
 * Não aceita dados da reserva nem metadados internos. Cancelamento usa a rota própria.
 */
public final class StatusConsultaHandler implements HttpHandler {
    private static final int MAX_BODY = 16384;
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final StatusConsultaService service;

    public StatusConsultaHandler(StatusConsultaService service) { this.service = service; }

    @Override public void handle(HttpExchange ex) throws IOException {
        var id = Query.objectId(ex.getRequestURI().getPath().split("/")[2], "id da consulta");
        StatusConsultaRequest pedido = lerCorpo(ex);
        Http.sendJson(ex, 200, service.alterar(id, pedido.status(), Autorizacao.usuario(ex)));
    }

    // Recusa campos extras e valores malformados antes de chamar o serviço, mantendo o
    // pedido restrito à alteração de status.
    private StatusConsultaRequest lerCorpo(HttpExchange ex) throws IOException {
        byte[] corpo = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (corpo.length > MAX_BODY) throw ApiException.badRequest("Dados excedem o tamanho permitido (16 KiB)");
        try {
            StatusConsultaRequest pedido = json.readValue(corpo, StatusConsultaRequest.class);
            if (pedido == null) throw ApiException.badRequest("Envie um objeto JSON com o campo status");
            return pedido;
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("JSON inválido. Envie somente o campo status com CONFIRMADA, CONCLUIDA ou FALTA");
        }
    }
}
