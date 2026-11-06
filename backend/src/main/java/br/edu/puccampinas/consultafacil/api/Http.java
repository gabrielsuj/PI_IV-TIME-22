package br.edu.puccampinas.consultafacil.api;

import br.edu.puccampinas.consultafacil.api.dto.ErrorResponse;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class Http {
    private Http() {}

    public static <T> T readJson(HttpExchange ex, Class<T> type) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        T obj;
        try {
            obj = Json.fromJson(body, type);
        } catch (JsonParseException e) {
            throw ApiException.badRequest("JSON inválido");
        }
        if (obj == null) throw ApiException.badRequest("Corpo da requisição vazio");
        return obj;
    }

    public static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static void sendError(HttpExchange ex, int status, String message) throws IOException {
        sendJson(ex, status, new ErrorResponse(status, message));
    }
}
