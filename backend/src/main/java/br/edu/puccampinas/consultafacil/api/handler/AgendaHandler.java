package br.edu.puccampinas.consultafacil.api.handler;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.ConflitoComConsultasException;
import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.api.dto.AgendaRequest;
import br.edu.puccampinas.consultafacil.service.AgendaService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bson.types.ObjectId;

import java.io.IOException;

/** GET e PUT /agendas?medicoId=... */
public class AgendaHandler implements HttpHandler {
    private final AgendaService service;

    public AgendaHandler(AgendaService service) { this.service = service; }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        // Perfil verificado em Rotas (#10). Pendente: restringir o MEDICO à própria agenda (medicos.usuarioId).
        ObjectId medicoId = Query.objectId(Query.obrigatorio(ex, "medicoId"), "medicoId");
        switch (ex.getRequestMethod()) {
            case "GET" -> Http.sendJson(ex, 200, service.buscar(medicoId));
            case "PUT" -> {
                try {
                    Http.sendJson(ex, 200, service.salvar(medicoId, Http.readJson(ex, AgendaRequest.class), Query.confirmado(ex)));
                } catch (ConflitoComConsultasException e) {
                    Http.sendJson(ex, 409, e.resposta()); // 409 com a lista das consultas afetadas (#44)
                }
            }
            default -> throw new ApiException(405, "Método não permitido");
        }
    }
}
