package br.edu.puccampinas.consultafacil.api.handler;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.api.dto.FilaEsperaRequest;
import br.edu.puccampinas.consultafacil.security.Autorizacao;
import br.edu.puccampinas.consultafacil.service.FilaEsperaService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

/**
 * Fila de espera (#31):
 *  POST   /fila-espera        → inscrever (201)
 *  GET    /fila-espera        → listar (?status=&pacienteId=&especialidadeId=)
 *  DELETE /fila-espera/{id}   → sair da fila (200)
 * Perfil verificado em Rotas.filaEspera; o serviço limita o paciente às próprias inscrições.
 */
public class FilaEsperaHandler implements HttpHandler {
    public static final String BASE = "/fila-espera";

    private final FilaEsperaService service;

    public FilaEsperaHandler(FilaEsperaService service) { this.service = service; }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String metodo = ex.getRequestMethod();
        String caminho = ex.getRequestURI().getPath();
        var ator = Autorizacao.usuario(ex);
        if (BASE.equals(caminho) && "POST".equals(metodo)) {
            Http.sendJson(ex, 201, service.inscrever(Http.readJson(ex, FilaEsperaRequest.class), ator));
            return;
        }
        if (BASE.equals(caminho) && "GET".equals(metodo)) {
            Map<String, String> q = Query.params(ex);
            Http.sendJson(ex, 200, service.listar(q.get("status"), q.get("pacienteId"), q.get("especialidadeId"), ator));
            return;
        }
        // Caminho: /fila-espera/{id} → partes ["", "fila-espera", "{id}"]
        String[] partes = caminho.split("/");
        if (partes.length == 3 && "DELETE".equals(metodo)) {
            Http.sendJson(ex, 200, service.sair(Query.objectId(partes[2], "id da inscrição"), ator));
            return;
        }
        throw ApiException.notFound("Rota não encontrada");
    }
}
