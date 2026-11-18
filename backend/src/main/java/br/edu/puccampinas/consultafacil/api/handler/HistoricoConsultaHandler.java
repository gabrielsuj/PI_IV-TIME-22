package br.edu.puccampinas.consultafacil.api.handler;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.security.Autorizacao;
import br.edu.puccampinas.consultafacil.service.HistoricoConsultaService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * GET /consultas/{id}/historico (#46): 200 com eventos projetados e limite da leitura;
 * 400 ID/parâmetro inválido, 401 sem sessão, 403 sem acesso à consulta, 404 consulta inexistente.
 * Lista vazia não prova ausência de ações, pois a projeção do histórico é eventual.
 */
public final class HistoricoConsultaHandler implements HttpHandler {
    private final HistoricoConsultaService service;

    public HistoricoConsultaHandler(HistoricoConsultaService service) { this.service = service; }

    @Override public void handle(HttpExchange ex) throws IOException {
        var id = Query.objectId(ex.getRequestURI().getPath().split("/")[2], "id da consulta");
        if (!Query.params(ex).isEmpty()) throw ApiException.badRequest("Parâmetros de consulta não permitidos");
        Http.sendJson(ex, 200, service.listar(id, Autorizacao.usuario(ex)));
    }
}
