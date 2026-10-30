package br.edu.puccampinas.consultafacil.api.middleware;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.util.List;

public final class Cors {
    private final List<String> allowedOrigins;

    public Cors(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }

    public void addHeaders(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null || !allowedOrigins.contains(origin)) return;
        Headers h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", origin);
        h.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        h.set("Access-Control-Allow-Credentials", "true");
        h.set("Vary", "Origin");
    }
}
