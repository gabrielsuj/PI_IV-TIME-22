package br.edu.puccampinas.consultafacil.api.handler;

import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.service.AgendamentoService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

/**
 * GET /vagas?medicoId=...&especialidadeId=...&data=AAAA-MM-DD   (um dia)
 * GET /vagas?medicoId=...&especialidadeId=...&de=AAAA-MM-DD&ate=AAAA-MM-DD   (intervalo, até 31 dias)
 */
public class VagasHandler implements HttpHandler {
    private final AgendamentoService service;

    public VagasHandler(AgendamentoService service) { this.service = service; }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        // Perfil verificado em Rotas (#10).
        Map<String, String> q = Query.params(ex);
        String de = q.get("de");
        String ate = q.get("ate");
        if (q.get("data") != null) {   // "data" é o atalho para um dia só
            de = q.get("data");
            ate = q.get("data");
        }
        Http.sendJson(ex, 200, service.buscarVagas(q.get("medicoId"), q.get("especialidadeId"), de, ate));
    }
}
