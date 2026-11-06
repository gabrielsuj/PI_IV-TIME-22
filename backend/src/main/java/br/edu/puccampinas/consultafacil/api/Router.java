package br.edu.puccampinas.consultafacil.api;

import br.edu.puccampinas.consultafacil.api.middleware.Cors;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Router implements HttpHandler {
    private final Map<String, HttpHandler> routes = new HashMap<>();
    private final Cors cors;

    public Router(Cors cors) { this.cors = cors; }

    public Router add(String method, String path, HttpHandler handler) {
        routes.put(method + " " + path, handler);
        return this;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            cors.addHeaders(ex);
            String method = ex.getRequestMethod();
            if (method.equals("OPTIONS")) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            HttpHandler handler = routes.get(method + " " + ex.getRequestURI().getPath());
            if (handler == null) throw ApiException.notFound("Rota não encontrada");
            handler.handle(ex);
        } catch (ApiException e) {
            Http.sendError(ex, e.status(), e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            Http.sendError(ex, 500, "Erro interno do servidor");
        } finally {
            ex.close();
        }
    }
}
