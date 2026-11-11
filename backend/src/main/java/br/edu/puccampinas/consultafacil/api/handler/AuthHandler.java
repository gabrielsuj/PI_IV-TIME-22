package br.edu.puccampinas.consultafacil.api.handler;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.dto.LoginRequest;
import br.edu.puccampinas.consultafacil.security.Autenticador;
import br.edu.puccampinas.consultafacil.service.AuthService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * POST /auth/login  → 200 {token, expiraEm, usuario}
 * POST /auth/logout → 204 (invalida o token enviado no Authorization)
 * GET  /auth/me     → 200 usuario da sessão, ou 401
 */
public class AuthHandler implements HttpHandler {
    private final AuthService service;
    private final Autenticador autenticador;

    public AuthHandler(AuthService service, Autenticador autenticador) {
        this.service = service;
        this.autenticador = autenticador;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String rota = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
        switch (rota) {
            case "POST /auth/login" -> Http.sendJson(ex, 200, service.login(Http.readJson(ex, LoginRequest.class)));
            case "POST /auth/logout" -> {
                service.logout(Autenticador.token(ex).orElse(null));
                ex.sendResponseHeaders(204, -1);
            }
            case "GET /auth/me" -> Http.sendJson(ex, 200, autenticador.exigir(ex));
            default -> throw ApiException.notFound("Rota não encontrada");
        }
    }
}
