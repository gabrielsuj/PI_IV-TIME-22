
package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.service.ValidadorCpfTcp;
import br.edu.puccampinas.consultafacil.domain.exception.ServidorCpfIndisponivelException;
import br.edu.puccampinas.consultafacil.domain.exception.ValidacaoException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.net.ServerSocket;
import java.util.concurrent.*;

class ValidadorCpfTcpTest {

    private final ValidadorCpfTcp validador =
        new ValidadorCpfTcp("localhost", 3000);

    @Test
    void deveAceitarCpfValido() {
        assertDoesNotThrow(() ->
            validador.validar("52998224725")
        );
    }

    @Test
    void deveRejeitarCpfInvalido() {
        assertThrows(ValidacaoException.class, () ->
            validador.validar("52998224726")
        );
    }

    @Test
    void deveRejeitarDigitosRepetidos() {
        assertThrows(ValidacaoException.class, () ->
            validador.validar("11111111111")
        );
    }


@Test
void deveFalharQuandoServidorNaoResponde() throws Exception {

    try (ServerSocket servidor = new ServerSocket(0)) {

        ExecutorService executor =
            Executors.newSingleThreadExecutor();

        Future<?> tarefa = executor.submit(() -> {
            try (var conexao = servidor.accept()) {
                Thread.sleep(5000);
            } catch (Exception e) {
                // Encerra a conexao simulada.
            }
        });

        try {
            ValidadorCpfTcp validador = new ValidadorCpfTcp(
                "127.0.0.1",
                servidor.getLocalPort()
            );

            assertThrows(
                ServidorCpfIndisponivelException.class,
                () -> validador.validar("52998224725")
            );

        } finally {
            servidor.close();
            executor.shutdownNow();
            tarefa.cancel(true);
        }
    }
}

}
