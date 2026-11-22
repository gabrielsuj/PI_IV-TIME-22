/*
Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

package br.edu.puccampinas.consultafacil.api.handler;

import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.security.Autorizacao;
import br.edu.puccampinas.consultafacil.service.CentralNotificacoesService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

public record CentralNotificacoesHandler(CentralNotificacoesService central) implements HttpHandler {
    private record Acao(String notificacaoId) {}

    @Override public void handle(HttpExchange ex) throws IOException {
        var ator = Autorizacao.usuario(ex);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if ("GET".equals(ex.getRequestMethod())) {
            Http.sendJson(ex, 200, central.listar(ator));
            return;
        }
        var id = Query.objectId(Http.readJson(ex, Acao.class).notificacaoId(), "notificacaoId");
        if ("/notificacoes/confirmar".equals(ex.getRequestURI().getPath())) central.confirmar(id, ator);
        else central.marcarLida(id, ator);
        ex.sendResponseHeaders(204, -1);
    }
}
