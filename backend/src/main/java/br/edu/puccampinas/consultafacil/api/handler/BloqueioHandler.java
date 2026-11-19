package br.edu.puccampinas.consultafacil.api.handler;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.ConflitoComConsultasException;
import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.api.dto.BloqueioRequest;
import br.edu.puccampinas.consultafacil.service.BloqueioService;
import br.edu.puccampinas.consultafacil.security.Autorizacao;
import br.edu.puccampinas.consultafacil.service.AcessoAgenda;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

/** GET /bloqueios?medicoId=...[&de=&ate=] · POST /bloqueios · DELETE /bloqueios?id=... */
public class BloqueioHandler implements HttpHandler {
    private final BloqueioService service;

    private final AcessoAgenda acesso;

    public BloqueioHandler(BloqueioService service, AcessoAgenda acesso) {
        this.service = service;
        this.acesso = acesso;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        Map<String, String> q = Query.params(ex);
        switch (ex.getRequestMethod()) {
            case "GET" -> Http.sendJson(ex, 200, service.listar(
                    acesso.medicoAutorizado(Autorizacao.usuario(ex), Query.obrigatorio(ex, "medicoId")), q.get("de"), q.get("ate")));
            case "POST" -> {
                try {
                    var pedido = Http.readJson(ex, BloqueioRequest.class);
                    var medico = acesso.medicoAutorizado(Autorizacao.usuario(ex), pedido.medicoId());
                    Http.sendJson(ex, 201, service.criar(medico, pedido, Query.confirmado(ex)));
                } catch (ConflitoComConsultasException e) {
                    Http.sendJson(ex, 409, e.resposta()); // 409 com a lista das consultas afetadas (#44)
                }
            }
            case "DELETE" -> {
                service.remover(Query.objectId(Query.obrigatorio(ex, "id"), "id"), acesso.restricao(Autorizacao.usuario(ex)));
                Http.sendJson(ex, 200, Map.of("removido", true));
            }
            default -> throw new ApiException(405, "Método não permitido");
        }
    }
}
