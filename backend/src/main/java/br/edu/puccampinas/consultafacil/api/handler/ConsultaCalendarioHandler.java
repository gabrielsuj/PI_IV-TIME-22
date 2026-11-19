package br.edu.puccampinas.consultafacil.api.handler;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.service.ConsultaCalendarioService;
import br.edu.puccampinas.consultafacil.security.Autorizacao;
import br.edu.puccampinas.consultafacil.service.AcessoAgenda;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

/** GET /consultas?medicoId=...&de=AAAA-MM-DD&ate=AAAA-MM-DD (calendário operacional, #23). */
public class ConsultaCalendarioHandler implements HttpHandler {
    private final ConsultaCalendarioService service;

    private final AcessoAgenda acesso;

    public ConsultaCalendarioHandler(ConsultaCalendarioService service, AcessoAgenda acesso) {
        this.service = service;
        this.acesso = acesso;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) throw new ApiException(405, "Método não permitido");
        Map<String, String> q = Query.params(ex);
        Http.sendJson(ex, 200, service.listar(
                acesso.medicoAutorizado(Autorizacao.usuario(ex), Query.obrigatorio(ex, "medicoId")), q.get("de"), q.get("ate")));
    }
}
