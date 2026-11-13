package br.edu.puccampinas.consultafacil.api.handler;

import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.security.Autorizacao;
import br.edu.puccampinas.consultafacil.service.ConsultaLeituraService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

/**
 * Leitura de consultas para as telas da #30:
 *  GET /consultas/lista?periodo=proximas|anteriores&status=&medicoId=  → lista
 *  GET /consultas/{id}                                                → detalhe
 * Perfil verificado em Rotas.consultaPorId; o serviço limita ao que o usuário pode ver.
 */
public class ConsultaLeituraHandler implements HttpHandler {
    public static final String LISTA = "/consultas/lista";

    private final ConsultaLeituraService service;

    public ConsultaLeituraHandler(ConsultaLeituraService service) { this.service = service; }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String caminho = ex.getRequestURI().getPath();
        var ator = Autorizacao.usuario(ex);
        if (LISTA.equals(caminho)) {
            Map<String, String> q = Query.params(ex);
            Http.sendJson(ex, 200, service.listar(q.get("periodo"), q.get("status"), q.get("medicoId"), ator));
            return;
        }
        // Caminho: /consultas/{id} → partes ["", "consultas", "{id}"]
        String id = caminho.split("/")[2];
        Http.sendJson(ex, 200, service.detalhar(Query.objectId(id, "id da consulta"), ator));
    }
}
