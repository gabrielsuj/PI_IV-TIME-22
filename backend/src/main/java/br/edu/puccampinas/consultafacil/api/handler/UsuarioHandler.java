package br.edu.puccampinas.consultafacil.api.handler;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.api.dto.AgendaRequest;
import br.edu.puccampinas.consultafacil.api.dto.UsuarioRequest;
import br.edu.puccampinas.consultafacil.service.UsuarioService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bson.types.ObjectId;

import java.io.IOException;

/** GET e PUT /agendas?medicoId=... */
public class UsuarioHandler implements HttpHandler {
    private final UsuarioService service;

    public UsuarioHandler(UsuarioService service) { this.service = service; }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        // TODO #10: permitir só o próprio médico e a secretaria
        ObjectId medicoId = Query.objectId(Query.obrigatorio(ex, "medicoId"), "medicoId");
        switch (ex.getRequestMethod()) {
            case "GET" -> Http.sendJson(ex, 200, service.buscar(medicoId));
            case "PUT" -> Http.sendJson(ex, 200, service.salvar(medicoId, Http.readJson(ex, AgendaRequest.class)));
            default -> throw new ApiException(405, "Método não permitido");
        }
    }
}

