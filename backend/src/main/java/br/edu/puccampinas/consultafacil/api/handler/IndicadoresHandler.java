package br.edu.puccampinas.consultafacil.api.handler;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.security.Autorizacao;
import br.edu.puccampinas.consultafacil.service.IndicadoresService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * GET /indicadores?de=AAAA-MM-DD&ate=AAAA-MM-DD[&medicoId=ObjectId]. Até 62 dias inclusivos.
 * Médico: próprio cadastro, filtro ausente/meu/próprio ID. Secretaria: todos ou ID específico.
 * 400 parâmetros inválidos; 401 sem sessão; 403 paciente/outro médico/sem vínculo; 404 filtro
 * de cadastro inexistente; 503 fonte indisponível. Não repete escrita nem altera fila/histórico.
 */
public final class IndicadoresHandler implements HttpHandler {
    private static final Set<String> PARAMETROS = Set.of("de", "ate", "medicoId");
    private final IndicadoresService service;

    public IndicadoresHandler(IndicadoresService service) { this.service = service; }

    @Override public void handle(HttpExchange ex) throws IOException {
        Map<String, String> parametros;
        try { parametros = Query.params(ex); }
        catch (IllegalArgumentException e) { throw ApiException.badRequest("Parâmetros de consulta inválidos"); }
        String query = ex.getRequestURI().getRawQuery();
        if (!PARAMETROS.containsAll(parametros.keySet())) throw ApiException.badRequest("Parâmetro de consulta não permitido");
        if (query != null && !query.isBlank() && query.split("&", -1).length != parametros.size())
            throw ApiException.badRequest("Parâmetros repetidos ou vazios não são permitidos");
        Http.sendJson(ex, 200, service.consultar(parametros.get("de"), parametros.get("ate"), parametros.get("medicoId"), Autorizacao.usuario(ex)));
    }
}
