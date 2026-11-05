package br.edu.puccampinas.consultafacil.api.handler;

import br.edu.puccampinas.consultafacil.api.dto.PacienteRequest;
import br.edu.puccampinas.consultafacil.domain.exception.ValidacaoException;
import br.edu.puccampinas.consultafacil.service.PacienteService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class PacienteHandler implements HttpHandler {
    private final PacienteService service;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public PacienteHandler(PacienteService service) { this.service = service; }

    @Override public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            boolean colecao = path.equals("/pacientes") || path.equals("/pacientes/");
            boolean individual = path.matches("/pacientes/[^/]+");
            if (!colecao && !individual) { responder(exchange, 404, erro("Rota não encontrada.")); return; }
            String id = individual ? path.substring("/pacientes/".length()) : null;
            switch (exchange.getRequestMethod()) {
                case "GET" -> {
                    if (colecao) responder(exchange, 200, service.listar(busca(exchange)));
                    else {
                        var paciente = service.consultar(id);
                        if (paciente.isPresent()) responder(exchange, 200, paciente.get());
                        else responder(exchange, 404, erro("Paciente não encontrado."));
                    }
                }
                case "POST" -> {
                    if (!colecao) { metodoInvalido(exchange, false); return; }
                    var paciente = service.cadastrar(lerRequest(exchange));
                    exchange.getResponseHeaders().set("Location", "/pacientes/" + paciente.id());
                    responder(exchange, 201, paciente);
                }
                case "PUT" -> {
                    if (colecao) { metodoInvalido(exchange, true); return; }
                    var paciente = service.atualizar(id, lerRequest(exchange));
                    if (paciente.isPresent()) responder(exchange, 200, paciente.get());
                    else responder(exchange, 404, erro("Paciente não encontrado."));
                }
                default -> metodoInvalido(exchange, colecao);
            }
        } catch (ValidacaoException e) {
            responder(exchange, 400, erro(e.getMessage()));
        } catch (JsonProcessingException e) {
            responder(exchange, 400, erro("JSON inválido. Envie um objeto com nome, cpf e email."));
        } catch (RuntimeException e) {
            responder(exchange, 500, erro("Não foi possível processar a solicitação."));
        } finally { exchange.close(); }
    }

    private PacienteRequest lerRequest(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(16385);
        if (body.length > 16384) throw new ValidacaoException("Dados do paciente excedem o tamanho permitido.");
        JsonNode node = json.readTree(body);
        if (node == null || !node.isObject()) throw new ValidacaoException("Envie um objeto JSON com nome, cpf e email.");
        return new PacienteRequest(campo(node, "nome"), campo(node, "cpf"), campo(node, "email"));
    }

    private String campo(JsonNode node, String campo) {
        JsonNode valor = node.get(campo);
        if (valor == null || valor.isNull()) return null;
        if (!valor.isTextual()) throw new ValidacaoException("O campo " + campo + " deve ser um texto.");
        return valor.textValue();
    }

    private String busca(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return "";
        try {
            for (String parametro : query.split("&")) {
                String[] partes = parametro.split("=", 2);
                if (URLDecoder.decode(partes[0], StandardCharsets.UTF_8).equals("busca"))
                    return partes.length == 2 ? URLDecoder.decode(partes[1], StandardCharsets.UTF_8) : "";
            }
            return "";
        } catch (IllegalArgumentException e) { throw new ValidacaoException("Parâmetro de busca inválido."); }
    }

    private void metodoInvalido(HttpExchange exchange, boolean colecao) throws IOException {
        exchange.getResponseHeaders().set("Allow", colecao ? "GET, POST" : "GET, PUT");
        responder(exchange, 405, erro("Método não permitido para esta rota."));
    }

    private Map<String, String> erro(String mensagem) { return Map.of("mensagem", mensagem); }

    private void responder(HttpExchange exchange, int status, Object dados) throws IOException {
        byte[] body = json.writeValueAsBytes(dados);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
