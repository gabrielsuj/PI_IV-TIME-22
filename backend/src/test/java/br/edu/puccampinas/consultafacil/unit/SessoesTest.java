package br.edu.puccampinas.consultafacil.unit;

import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.security.Sessoes;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import br.edu.puccampinas.consultafacil.support.RelogioAjustavel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SessoesTest {
    private final RelogioAjustavel relogio = new RelogioAjustavel(Instant.parse("2026-11-12T12:00:00Z"));
    private final Sessoes sessoes = new Sessoes(Duration.ofMinutes(60), relogio);
    private final UsuarioLogado ana = new UsuarioLogado("u1", "Ana", Perfil.SECRETARIA_ADMIN);

    @Test void deveValidarTokenRecemCriado() {
        var s = sessoes.criar(ana);
        assertEquals(ana, sessoes.validar(s.token()).orElseThrow());
        assertEquals(Instant.parse("2026-11-12T13:00:00Z"), s.expiraEm());
    }

    @Test void deveGerarTokensDiferentes() {
        assertNotEquals(sessoes.criar(ana).token(), sessoes.criar(ana).token());
    }

    @Test void deveExpirarAposValidade() {
        var s = sessoes.criar(ana);
        relogio.avancar(Duration.ofMinutes(59));
        assertTrue(sessoes.validar(s.token()).isPresent());
        relogio.avancar(Duration.ofMinutes(1));
        assertTrue(sessoes.validar(s.token()).isEmpty());
    }

    @Test void deveInvalidarNoLogout() {
        var s = sessoes.criar(ana);
        sessoes.encerrar(s.token());
        assertTrue(sessoes.validar(s.token()).isEmpty());
    }

    @Test void deveRecusarTokenDesconhecidoOuNulo() {
        assertTrue(sessoes.validar("inventado").isEmpty());
        assertTrue(sessoes.validar(null).isEmpty());
    }
}
