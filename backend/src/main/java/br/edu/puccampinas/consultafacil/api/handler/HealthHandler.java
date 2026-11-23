/*
Autoria: Murilo Moraes
RA: 25000073
*/
package br.edu.puccampinas.consultafacil.api.handler;

import br.edu.puccampinas.consultafacil.api.Http;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;

public class HealthHandler implements HttpHandler {
    // Indica apenas que o processo HTTP responde.
    @Override
    public void handle(HttpExchange ex) throws IOException {
        Http.sendJson(ex, 200, Map.of("status", "ok"));
    }
}
