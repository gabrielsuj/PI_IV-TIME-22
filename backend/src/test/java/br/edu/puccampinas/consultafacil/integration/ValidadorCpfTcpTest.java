
package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.client.Cliente;

import br.edu.puccampinas.consultafacil.service.ValidadorCpfTcp;
import br.edu.puccampinas.consultafacil.domain.exception.ServidorCpfIndisponivelException;
import br.edu.puccampinas.consultafacil.domain.exception.ValidacaoException;
import br.edu.puccampinas.consultafacil.handler.ValidarCPFRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.concurrent.*;

class ValidadorCpfTcpTest {

    private final ValidadorCpfTcp validador =
        new ValidadorCpfTcp("localhost", Cliente.PORTA_PADRAO);

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
@Timeout(10)
void deveFalharQuandoServidorFechaConexaoSemResponder() throws Exception {

    try (ServerSocket servidor = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
        servidor.setSoTimeout(3000);

        ExecutorService executor =
            Executors.newFixedThreadPool(2);
        CountDownLatch recebeu = new CountDownLatch(1), fechar = new CountDownLatch(1);

        Future<Void> tarefa = executor.submit(() -> {
            try (var conexao = servidor.accept()) {
                conexao.setSoTimeout(3000);
                try (ObjectOutputStream saida = new ObjectOutputStream(conexao.getOutputStream());
                     ObjectInputStream entrada = new ObjectInputStream(conexao.getInputStream())) {
                    assertInstanceOf(ValidarCPFRequest.class, entrada.readObject());
                    recebeu.countDown();
                    assertTrue(fechar.await(3, TimeUnit.SECONDS));
                }
            }
            return null;
        });

        try {
            ValidadorCpfTcp validador = new ValidadorCpfTcp(
                servidor.getInetAddress().getHostAddress(),
                servidor.getLocalPort()
            );

            Future<ServidorCpfIndisponivelException> falha = executor.submit(() -> assertThrows(
                    ServidorCpfIndisponivelException.class, () -> validador.validar("52998224725")));
            assertTrue(recebeu.await(3, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> falha.get(100, TimeUnit.MILLISECONDS));
            fechar.countDown();
            assertNotNull(falha.get(3, TimeUnit.SECONDS).getCause());
            tarefa.get(3, TimeUnit.SECONDS);

        } finally {
            fechar.countDown();
            servidor.close();
            executor.shutdownNow();
            tarefa.cancel(true);
        }
    }
}

}
