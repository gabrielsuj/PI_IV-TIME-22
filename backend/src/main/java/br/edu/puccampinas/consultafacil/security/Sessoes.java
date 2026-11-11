package br.edu.puccampinas.consultafacil.security;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sessões em memória: token aleatório → usuário, com expiração.
 * Reiniciar o servidor encerra todas as sessões (aceitável no MVP).
 * O Clock é injetável para os testes simularem a passagem do tempo.
 */
public class Sessoes {
    public record Sessao(String token, UsuarioLogado usuario, Instant expiraEm) {}

    private final Map<String, Sessao> ativas = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Duration validade;
    private final Clock relogio;

    public Sessoes(Duration validade) { this(validade, Clock.systemUTC()); }

    public Sessoes(Duration validade, Clock relogio) {
        this.validade = validade;
        this.relogio = relogio;
    }

    public Sessao criar(UsuarioLogado usuario) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var sessao = new Sessao(token, usuario, relogio.instant().plus(validade));
        ativas.put(token, sessao);
        return sessao;
    }

    /** Usuário da sessão, se o token existir e não tiver expirado. Sessões vencidas são descartadas. */
    public Optional<UsuarioLogado> validar(String token) {
        if (token == null) return Optional.empty();
        Sessao s = ativas.get(token);
        if (s == null) return Optional.empty();
        if (!relogio.instant().isBefore(s.expiraEm())) {
            ativas.remove(token);
            return Optional.empty();
        }
        return Optional.of(s.usuario());
    }

    /** Logout: o token deixa de valer imediatamente. */
    public void encerrar(String token) {
        if (token != null) ativas.remove(token);
    }
}
