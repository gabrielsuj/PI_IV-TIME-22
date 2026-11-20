package br.edu.puccampinas.consultafacil.client;

import br.edu.puccampinas.consultafacil.handler.ValidarCPFRequest;
import br.edu.puccampinas.consultafacil.handler.ValidarCPFResponse;
import br.edu.puccampinas.consultafacil.protocol.PedidoParaSair;
import org.junit.Test;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class ClienteTest {
    @Test(timeout = 10000)
    public void consomeRespostasDeValidacoesConsecutivasNaMesmaConexao() throws Exception {
        String[] cpfs = {"52998224725", "11111111111"};
        ExecutorService executor = Executors.newSingleThreadExecutor(tarefa -> {
            Thread thread = new Thread(tarefa);
            thread.setDaemon(true);
            return thread;
        });
        try (ServerSocket servidor = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            servidor.setSoTimeout(3000);
            Future<Void> atendimento = executor.submit(() -> {
                try (Socket conexao = servidor.accept()) {
                    conexao.setSoTimeout(3000);
                    try (ObjectOutputStream saida = new ObjectOutputStream(conexao.getOutputStream());
                         ObjectInputStream entrada = new ObjectInputStream(conexao.getInputStream())) {
                        for (int i = 0; i < cpfs.length; i++) {
                            ValidarCPFRequest pedido = (ValidarCPFRequest) entrada.readObject();
                            assertEquals(cpfs[i], pedido.getCPF());
                            saida.writeObject(new ValidarCPFResponse(pedido.getCPF(), i == 0,
                                    i == 0 ? "OK" : "CPF invalido"));
                            saida.flush();
                        }
                        assertTrue(entrada.readObject() instanceof PedidoParaSair);
                    }
                }
                return null;
            });

            try (Cliente cliente = new Cliente(servidor.getInetAddress().getHostAddress(), servidor.getLocalPort())) {
                ValidarCPFResponse primeira = cliente.validarCPF(new ValidarCPFRequest(cpfs[0]));
                assertEquals(cpfs[0], primeira.getCPF());
                assertTrue(primeira.isValido());

                ValidarCPFResponse segunda = cliente.validarCPF(new ValidarCPFRequest(cpfs[1]));
                assertEquals(cpfs[1], segunda.getCPF());
                assertFalse(segunda.isValido());
            }
            atendimento.get(3, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }
}
