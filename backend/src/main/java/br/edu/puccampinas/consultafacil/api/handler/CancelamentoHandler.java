package br.edu.puccampinas.consultafacil.api.handler;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.Json;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.api.dto.CancelamentoRequest;
import br.edu.puccampinas.consultafacil.security.Autorizacao;
import br.edu.puccampinas.consultafacil.service.CancelamentoService;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * POST /consultas/{id}/cancelamento (#27). Corpo opcional: {"motivo": "..."}.
 * 200 com a consulta cancelada; 403 sem acesso à consulta; 404 se não existe; 409 se já finalizada ou já começou.
 * Perfil verificado em Rotas.consultaPorId; o serviço confere se a consulta é de quem chama.
 */
public class CancelamentoHandler implements HttpHandler {
    private final CancelamentoService service;

    public CancelamentoHandler(CancelamentoService service) { this.service = service; }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        // Caminho: /consultas/{id}/cancelamento → partes ["", "consultas", "{id}", "cancelamento"]
        String id = ex.getRequestURI().getPath().split("/")[2];
        CancelamentoRequest pedido = lerCorpoOpcional(ex);
        Http.sendJson(ex, 200, service.cancelar(Query.objectId(id, "id da consulta"),
                pedido.motivo(), Autorizacao.usuario(ex)));
    }

    /** O motivo é opcional, então aceita corpo vazio (Http.readJson recusaria). */
    private static CancelamentoRequest lerCorpoOpcional(HttpExchange ex) throws IOException {
        String corpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (corpo.isBlank()) return new CancelamentoRequest(null);
        try {
            CancelamentoRequest pedido = Json.fromJson(corpo, CancelamentoRequest.class);
            return pedido == null ? new CancelamentoRequest(null) : pedido;
        } catch (JsonParseException e) {
            throw ApiException.badRequest("JSON inválido");
        }
    }
}
