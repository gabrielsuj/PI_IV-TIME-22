package br.edu.puccampinas.consultafacil.unit;

import br.edu.puccampinas.consultafacil.security.SenhaHash;
import br.edu.puccampinas.consultafacil.security.SenhaHasher;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class SenhaHasherTest {
    private final SenhaHasher hasher = new SenhaHasher(1_000);

    @Test void deveConferirSenhaCorreta() {
        SenhaHash h = hasher.gerar("segredo123");
        assertTrue(hasher.confere("segredo123", h));
    }

    @Test void deveRecusarSenhaErrada() {
        SenhaHash h = hasher.gerar("segredo123");
        assertFalse(hasher.confere("segredo124", h));
        assertFalse(hasher.confere("", h));
        assertFalse(hasher.confere(null, h));
    }

    @Test void naoDeveGuardarSenhaEmTextoPuro() {
        SenhaHash h = hasher.gerar("segredo123");
        assertFalse(h.hash().contains("segredo123"));
        assertEquals(SenhaHasher.ALGORITMO, h.algoritmo());
        assertEquals(1_000, h.iteracoes());
        assertTrue(Base64.getDecoder().decode(h.salt()).length >= 16);
    }

    @Test void deveUsarSaltDiferenteACadaHash() {
        SenhaHash a = hasher.gerar("mesma");
        SenhaHash b = hasher.gerar("mesma");
        assertNotEquals(a.salt(), b.salt());
        assertNotEquals(a.hash(), b.hash());
    }

    @Test void deveConferirHashGeradoComOutroNumeroDeIteracoes() {
        SenhaHash antigo = new SenhaHasher(500).gerar("segredo123");
        assertTrue(hasher.confere("segredo123", antigo));
    }

    @Test void deveRecusarAlgoritmoDesconhecido() {
        SenhaHash h = hasher.gerar("segredo123");
        var outro = new SenhaHash(h.hash(), h.salt(), "MD5", h.iteracoes());
        assertFalse(hasher.confere("segredo123", outro));
    }
}
