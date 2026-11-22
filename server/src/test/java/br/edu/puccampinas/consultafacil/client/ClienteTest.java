package br.edu.puccampinas.consultafacil.client;

import br.edu.puccampinas.consultafacil.handler.ValidarCPFRequest;
import br.edu.puccampinas.consultafacil.handler.ValidarCPFResponse;
import br.edu.puccampinas.consultafacil.connection.TratadoraDeComunicadoDeDesligamento;
import br.edu.puccampinas.consultafacil.protocol.Comunicado;
import br.edu.puccampinas.consultafacil.protocol.ComunicadoDeDesligamento;
import br.edu.puccampinas.consultafacil.protocol.PedidoParaSair;
import org.junit.After;
import org.junit.Test;

import java.io.EOFException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class ClienteTest {
    @After
    public void naoDeixaTratadorasAtivas() throws Exception {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread instanceof TratadoraDeComunicadoDeDesligamento) {
                thread.join(1000);
                assertFalse("Tratadora permaneceu ativa", thread.isAlive());
            }
        }
    }

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

    @Test(timeout = 10000)
    public void mantemRespostasIndependentesComDoisClientes() throws Exception {
        CountDownLatch pedidos = new CountDownLatch(2);
        ExecutorService solicitacoes = executor();
        try (ServidorTeste servidor = new ServidorTeste()) {
            Atendimento responder = (conexao, entrada, saida) -> {
                ValidarCPFRequest pedido = (ValidarCPFRequest) entrada.readObject();
                pedidos.countDown();
                assertTrue(pedidos.await(3, TimeUnit.SECONDS));
                saida.writeObject(new ValidarCPFResponse(pedido.getCPF(), pedido.getCPF().equals("52998224725"), "Resposta"));
                saida.flush();
                assertTrue(entrada.readObject() instanceof PedidoParaSair);
            };
            Future<Void> primeiroAtendimento = servidor.atender(responder);
            Future<Void> segundoAtendimento = servidor.atender(responder);
            try (Cliente primeiro = servidor.cliente(); Cliente segundo = servidor.cliente()) {
                Future<ValidarCPFResponse> a = solicitacoes.submit(() -> primeiro.validarCPF(new ValidarCPFRequest("52998224725")));
                Future<ValidarCPFResponse> b = solicitacoes.submit(() -> segundo.validarCPF(new ValidarCPFRequest("11111111111")));
                ValidarCPFResponse respostaA = a.get(3, TimeUnit.SECONDS);
                ValidarCPFResponse respostaB = b.get(3, TimeUnit.SECONDS);
                assertEquals("52998224725", respostaA.getCPF());
                assertTrue(respostaA.isValido());
                assertEquals("11111111111", respostaB.getCPF());
                assertFalse(respostaB.isValido());
            }
            primeiroAtendimento.get(3, TimeUnit.SECONDS);
            segundoAtendimento.get(3, TimeUnit.SECONDS);
        } finally {
            solicitacoes.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void detectaDesligamentoOciosoSemEncerrarOProcesso() throws Exception {
        CountDownLatch desligar = new CountDownLatch(1);
        try (ServidorTeste servidor = new ServidorTeste()) {
            Future<Void> atendimento = servidor.atender((conexao, entrada, saida) -> {
                assertTrue(desligar.await(3, TimeUnit.SECONDS));
                saida.writeObject(new ComunicadoDeDesligamento());
                saida.flush();
                assertThrows(EOFException.class, entrada::readObject);
            });
            try (Cliente cliente = servidor.cliente()) {
                desligar.countDown();
                atendimento.get(3, TimeUnit.SECONDS);
                assertThrows(Exception.class, () -> cliente.validarCPF(new ValidarCPFRequest("52998224725")));
            }
        }
    }

    @Test(timeout = 10000)
    public void desligamentoInterrompeValidacaoPendente() throws Exception {
        falhaDuranteValidacao(new ComunicadoDeDesligamento());
    }

    @Test(timeout = 10000)
    public void respostaInesperadaFalhaSemDescartarMensagensEmLoop() throws Exception {
        falhaDuranteValidacao(new Comunicado());
    }

    private void falhaDuranteValidacao(Comunicado comunicado) throws Exception {
        try (ServidorTeste servidor = new ServidorTeste()) {
            Future<Void> atendimento = servidor.atender((conexao, entrada, saida) -> {
                assertTrue(entrada.readObject() instanceof ValidarCPFRequest);
                saida.writeObject(comunicado);
                saida.flush();
                assertThrows(EOFException.class, entrada::readObject);
            });
            try (Cliente cliente = servidor.cliente()) {
                assertThrows(Exception.class, () -> cliente.validarCPF(new ValidarCPFRequest("52998224725")));
                atendimento.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 10000)
    public void quedaAbruptaTerminaLeituraERecusaNovosPedidos() throws Exception {
        try (ServidorTeste servidor = new ServidorTeste()) {
            Future<Void> atendimento = servidor.atender((conexao, entrada, saida) ->
                    assertTrue(entrada.readObject() instanceof ValidarCPFRequest));
            try (Cliente cliente = servidor.cliente()) {
                assertThrows(Exception.class, () -> cliente.validarCPF(new ValidarCPFRequest("52998224725")));
                atendimento.get(3, TimeUnit.SECONDS);
                assertThrows(Exception.class, () -> cliente.validarCPF(new ValidarCPFRequest("11111111111")));
            }
        }
    }

    @Test(timeout = 10000)
    public void eofOciosoEncerraAConexaoSemEsperarUmPedido() throws Exception {
        try (ServidorTeste servidor = new ServidorTeste()) {
            Future<Void> atendimento = servidor.atender((conexao, entrada, saida) -> {
                conexao.shutdownOutput();
                assertThrows(EOFException.class, entrada::readObject);
            });
            try (Cliente cliente = servidor.cliente()) {
                atendimento.get(3, TimeUnit.SECONDS);
                assertThrows(Exception.class, () -> cliente.validarCPF(new ValidarCPFRequest("52998224725")));
            }
        }
    }

    @Test(timeout = 10000)
    public void closeRepetidoEnviaUmUnicoPedidoELiberaAConexao() throws Exception {
        try (ServidorTeste servidor = new ServidorTeste()) {
            Future<Void> atendimento = servidor.atender((conexao, entrada, saida) -> {
                assertTrue(entrada.readObject() instanceof PedidoParaSair);
                assertThrows(EOFException.class, entrada::readObject);
            });
            try (Cliente cliente = servidor.cliente()) {
                cliente.close();
                cliente.close();
                atendimento.get(3, TimeUnit.SECONDS);
                assertThrows(Exception.class, () -> cliente.validarCPF(new ValidarCPFRequest("52998224725")));
            }
        }
    }

    @Test(timeout = 10000)
    public void closeDesbloqueiaValidacaoSemEsperarOTimeoutDoSocket() throws Exception {
        CountDownLatch pedidoRecebido = new CountDownLatch(1);
        ExecutorService solicitacoes = executor();
        try (ServidorTeste servidor = new ServidorTeste()) {
            Future<Void> atendimento = servidor.atender((conexao, entrada, saida) -> {
                assertTrue(entrada.readObject() instanceof ValidarCPFRequest);
                pedidoRecebido.countDown();
                assertTrue(entrada.readObject() instanceof PedidoParaSair);
                assertThrows(EOFException.class, entrada::readObject);
            });
            try (Cliente cliente = new Cliente(servidor.host(), servidor.porta(), 3000, 0)) {
                Future<Exception> falha = solicitacoes.submit(() -> assertThrows(Exception.class,
                        () -> cliente.validarCPF(new ValidarCPFRequest("52998224725"))));
                assertTrue(pedidoRecebido.await(3, TimeUnit.SECONDS));
                cliente.close();
                assertNotNull(falha.get(3, TimeUnit.SECONDS));
                atendimento.get(3, TimeUnit.SECONDS);
            }
        } finally {
            solicitacoes.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void preservaTimeoutDeLeituraConfigurado() throws Exception {
        CountDownLatch liberar = new CountDownLatch(1);
        try (ServidorTeste servidor = new ServidorTeste()) {
            Future<Void> atendimento = servidor.atender((conexao, entrada, saida) -> {
                assertTrue(entrada.readObject() instanceof ValidarCPFRequest);
                assertTrue(liberar.await(3, TimeUnit.SECONDS));
            });
            try (Cliente cliente = new Cliente(servidor.host(), servidor.porta(), 3000, 200)) {
                Exception erro = assertThrows(Exception.class,
                        () -> cliente.validarCPF(new ValidarCPFRequest("52998224725")));
                Throwable causa = erro;
                while (causa.getCause() != null) causa = causa.getCause();
                assertTrue(causa instanceof SocketTimeoutException);
            } finally {
                liberar.countDown();
            }
            atendimento.get(3, TimeUnit.SECONDS);
        }
    }

    private static ExecutorService executor() {
        return Executors.newFixedThreadPool(2, tarefa -> {
            Thread thread = new Thread(tarefa);
            thread.setDaemon(true);
            return thread;
        });
    }

    @FunctionalInterface
    private interface Atendimento {
        void executar(Socket conexao, ObjectInputStream entrada, ObjectOutputStream saida) throws Exception;
    }

    private static final class ServidorTeste implements AutoCloseable {
        private final ServerSocket servidor = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
        private final ExecutorService executor = executor();
        private final List<Socket> conexoes = new CopyOnWriteArrayList<>();

        private ServidorTeste() throws Exception { servidor.setSoTimeout(3000); }
        private String host() { return servidor.getInetAddress().getHostAddress(); }
        private int porta() { return servidor.getLocalPort(); }
        private Cliente cliente() throws Exception { return new Cliente(host(), porta()); }

        private Future<Void> atender(Atendimento atendimento) {
            return executor.submit(() -> {
                try (Socket conexao = servidor.accept()) {
                    conexoes.add(conexao);
                    conexao.setSoTimeout(3000);
                    try (ObjectOutputStream saida = new ObjectOutputStream(conexao.getOutputStream());
                         ObjectInputStream entrada = new ObjectInputStream(conexao.getInputStream())) {
                        atendimento.executar(conexao, entrada, saida);
                    }
                }
                return null;
            });
        }

        @Override public void close() throws Exception {
            servidor.close();
            for (Socket conexao : conexoes) conexao.close();
            executor.shutdownNow();
        }
    }
}
