package br.edu.puccampinas.consultafacil.api.handler;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.api.dto.BloqueioRequest;
import br.edu.puccampinas.consultafacil.service.BloqueioService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

/** GET /bloqueios?medicoId=...[&de=&ate=] · POST /bloqueios · DELETE /bloqueios?id=... */
public class BloqueioHandler implements HttpHandler {
    private final BloqueioService service;

    public BloqueioHandler(BloqueioService service) { this.service = service; }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        // TODO #10: permitir só o próprio médico e a secretaria
        Map<String, String> q = Query.params(ex);
        switch (ex.getRequestMethod()) {
            case "GET" -> Http.sendJson(ex, 200, service.listar(
                    Query.objectId(Query.obrigatorio(ex, "medicoId"), "medicoId"), q.get("de"), q.get("ate")));
            case "POST" -> Http.sendJson(ex, 201, service.criar(Http.readJson(ex, BloqueioRequest.class)));
            case "DELETE" -> {
                service.remover(Query.objectId(Query.obrigatorio(ex, "id"), "id"));
                Http.sendJson(ex, 200, Map.of("removido", true));
            }
            default -> throw new ApiException(405, "Método não permitido");
        }
    }
}
