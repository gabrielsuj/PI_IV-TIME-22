package br.edu.puccampinas.consultafacil.api.handler;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.security.Autorizacao;
import br.edu.puccampinas.consultafacil.service.HistoricoConsultaService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * GET /consultas/{id}/historico (#46): 200 com eventos projetados e limite da leitura;
 * 400 ID/parâmetro inválido, 401 sem sessão, 403 sem acesso à consulta, 404 consulta inexistente.
 * Lista vazia não prova ausência de ações, pois a projeção do histórico é eventual.
 * Query opcional: aposSequencia (0..2147483647, padrão 0) e limite (1..100, padrão 100).
 * O cursor é exclusivo; continue com proximaSequencia enquanto temMais for verdadeiro.
 */
public final class HistoricoConsultaHandler implements HttpHandler {
    private final HistoricoConsultaService service;
    private static final Set<String> PARAMETROS = Set.of("aposSequencia", "limite");

    public HistoricoConsultaHandler(HistoricoConsultaService service) { this.service = service; }

    @Override public void handle(HttpExchange ex) throws IOException {
        var id = Query.objectId(ex.getRequestURI().getPath().split("/")[2], "id da consulta");
        Map<String, String> parametros = parametros(ex);
        int apos = inteiro(parametros, "aposSequencia", 0);
        int limite = inteiro(parametros, "limite", HistoricoConsultaService.LIMITE_PADRAO);
        Http.sendJson(ex, 200, service.listar(id, apos, limite, Autorizacao.usuario(ex)));
    }

    private static Map<String, String> parametros(HttpExchange ex) {
        Map<String, String> parametros;
        try { parametros = Query.params(ex); }
        catch (IllegalArgumentException e) { throw ApiException.badRequest("Parâmetros de consulta inválidos"); }
        String query = ex.getRequestURI().getRawQuery();
        if (!PARAMETROS.containsAll(parametros.keySet())) throw ApiException.badRequest("Parâmetro de consulta não permitido");
        // Query.params usa Map; a contagem detecta chaves repetidas, inclusive nomes codificados na URL.
        if (query != null && !query.isBlank() && query.split("&", -1).length != parametros.size())
            throw ApiException.badRequest("Parâmetros repetidos ou vazios não são permitidos");
        return parametros;
    }

    private static int inteiro(Map<String, String> parametros, String nome, int padrao) {
        String valor = parametros.get(nome);
        if (valor == null) return padrao;
        if (!valor.matches("[0-9]+")) throw ApiException.badRequest(nome + " deve ser um inteiro não negativo");
        try { return Integer.parseInt(valor); }
        catch (NumberFormatException e) { throw ApiException.badRequest(nome + " excede o valor permitido"); }
    }
}
