/*
Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

package br.edu.puccampinas.consultafacil.api.handler;

import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.security.Autorizacao;
import br.edu.puccampinas.consultafacil.service.AvisoVagaService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

public final class AvisoVagaHandler implements HttpHandler {
    public record AgendarAvisoRequest(String avisoId) {}

    private final AvisoVagaService avisos;

    public AvisoVagaHandler(AvisoVagaService avisos) { this.avisos = avisos; }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        var ator = Autorizacao.usuario(ex);
        if ("GET".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            Http.sendJson(ex, 200, avisos.listar(ator));
            return;
        }
        var pedido = Http.readJson(ex, AgendarAvisoRequest.class);
        Http.sendJson(ex, 201, avisos.agendar(Query.objectId(pedido.avisoId(), "avisoId"), ator));
    }
}
