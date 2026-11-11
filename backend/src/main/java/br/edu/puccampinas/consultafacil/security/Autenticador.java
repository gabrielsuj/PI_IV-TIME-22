package br.edu.puccampinas.consultafacil.security;

import br.edu.puccampinas.consultafacil.api.ApiException;
import com.sun.net.httpserver.HttpExchange;

import java.util.Optional;

/** Lê o cabeçalho `Authorization: Bearer <token>` e identifica quem fez a requisição. */
public class Autenticador {
    private static final String PREFIXO = "Bearer ";
    private final Sessoes sessoes;

    public Autenticador(Sessoes sessoes) { this.sessoes = sessoes; }

    /** Usuário da requisição, ou 401 se não houver token válido. */
    public UsuarioLogado exigir(HttpExchange ex) {
        return sessoes.validar(token(ex).orElse(null))
                .orElseThrow(() -> ApiException.unauthorized("Sessão inválida ou expirada. Faça login novamente."));
    }

    public static Optional<String> token(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.regionMatches(true, 0, PREFIXO, 0, PREFIXO.length())) return Optional.empty();
        String t = h.substring(PREFIXO.length()).trim();
        return t.isEmpty() ? Optional.empty() : Optional.of(t);
    }
}
