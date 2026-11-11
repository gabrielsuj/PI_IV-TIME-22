package br.edu.puccampinas.consultafacil.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Hash de senhas com PBKDF2 (disponível no próprio JDK).
 * O hash guarda algoritmo e iterações, então senhas antigas continuam válidas se o custo aumentar.
 */
public class SenhaHasher {
    public static final String ALGORITMO = "PBKDF2WithHmacSHA256";
    public static final int ITERACOES_PADRAO = 210_000;
    private static final int TAMANHO_SALT = 16;     // bytes
    private static final int TAMANHO_HASH = 256;    // bits (32 bytes)

    private final SecureRandom random = new SecureRandom();
    private final int iteracoes;

    public SenhaHasher() { this(ITERACOES_PADRAO); }

    /** Os testes usam poucas iterações para não ficarem lentos. */
    public SenhaHasher(int iteracoes) { this.iteracoes = iteracoes; }

    public SenhaHash gerar(String senha) {
        byte[] salt = new byte[TAMANHO_SALT];
        random.nextBytes(salt);
        byte[] hash = derivar(senha, salt, iteracoes);
        var b64 = Base64.getEncoder();
        return new SenhaHash(b64.encodeToString(hash), b64.encodeToString(salt), ALGORITMO, iteracoes);
    }

    public boolean confere(String senha, SenhaHash guardada) {
        if (senha == null || guardada == null || !ALGORITMO.equals(guardada.algoritmo())) return false;
        var b64 = Base64.getDecoder();
        byte[] esperado = b64.decode(guardada.hash());
        byte[] calculado = derivar(senha, b64.decode(guardada.salt()), guardada.iteracoes());
        // Comparação em tempo constante: não revela quantos bytes acertou.
        return MessageDigest.isEqual(esperado, calculado);
    }

    private static byte[] derivar(String senha, byte[] salt, int iteracoes) {
        var spec = new PBEKeySpec(senha.toCharArray(), salt, iteracoes, TAMANHO_HASH);
        try {
            return SecretKeyFactory.getInstance(ALGORITMO).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 indisponível no JDK", e);
        } finally {
            spec.clearPassword();
        }
    }
}
