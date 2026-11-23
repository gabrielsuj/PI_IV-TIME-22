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
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
                    conexao.setSoTimeout(10000);
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

            Cliente cliente = new Cliente(servidor.getInetAddress().getHostAddress(), servidor.getLocalPort());
            try {
                ValidarCPFResponse primeira = cliente.validarCPF(new ValidarCPFRequest(cpfs[0]));
                assertEquals(cpfs[0], primeira.getCPF());
                assertTrue(primeira.isValido());

                ValidarCPFResponse segunda = cliente.validarCPF(new ValidarCPFRequest(cpfs[1]));
                assertEquals(cpfs[1], segunda.getCPF());
                assertFalse(segunda.isValido());
            } finally {
                cliente.close();
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
            Cliente primeiro = servidor.cliente();
            try {
                Cliente segundo = servidor.cliente();
                try {
                    Future<ValidarCPFResponse> a = solicitacoes.submit(() -> primeiro.validarCPF(new ValidarCPFRequest("52998224725")));
                    Future<ValidarCPFResponse> b = solicitacoes.submit(() -> segundo.validarCPF(new ValidarCPFRequest("11111111111")));
                    ValidarCPFResponse respostaA = a.get(3, TimeUnit.SECONDS);
                    ValidarCPFResponse respostaB = b.get(3, TimeUnit.SECONDS);
                    assertEquals("52998224725", respostaA.getCPF());
                    assertTrue(respostaA.isValido());
                    assertEquals("11111111111", respostaB.getCPF());
                    assertFalse(respostaB.isValido());
                } finally {
                    segundo.close();
                }
            } finally {
                primeiro.close();
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
            Cliente cliente = servidor.cliente();
            try {
                desligar.countDown();
                atendimento.get(3, TimeUnit.SECONDS);
                assertThrows(Exception.class, () -> cliente.validarCPF(new ValidarCPFRequest("52998224725")));
            } finally {
                cliente.close();
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
            Cliente cliente = servidor.cliente();
            try {
                assertThrows(Exception.class, () -> cliente.validarCPF(new ValidarCPFRequest("52998224725")));
                atendimento.get(3, TimeUnit.SECONDS);
            } finally {
                cliente.close();
            }
        }
    }

    @Test(timeout = 10000)
    public void quedaAbruptaTerminaLeituraERecusaNovosPedidos() throws Exception {
        try (ServidorTeste servidor = new ServidorTeste()) {
            Future<Void> atendimento = servidor.atender((conexao, entrada, saida) ->
                    assertTrue(entrada.readObject() instanceof ValidarCPFRequest));
            Cliente cliente = servidor.cliente();
            try {
                assertThrows(Exception.class, () -> cliente.validarCPF(new ValidarCPFRequest("52998224725")));
                atendimento.get(3, TimeUnit.SECONDS);
                assertThrows(Exception.class, () -> cliente.validarCPF(new ValidarCPFRequest("11111111111")));
            } finally {
                cliente.close();
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
            Cliente cliente = servidor.cliente();
            try {
                atendimento.get(3, TimeUnit.SECONDS);
                assertThrows(Exception.class, () -> cliente.validarCPF(new ValidarCPFRequest("52998224725")));
            } finally {
                cliente.close();
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
            Cliente cliente = servidor.cliente();
            try {
                cliente.close();
                cliente.close();
                atendimento.get(3, TimeUnit.SECONDS);
                assertThrows(Exception.class, () -> cliente.validarCPF(new ValidarCPFRequest("52998224725")));
            } finally {
                cliente.close();
            }
        }
    }

    @Test(timeout = 10000)
    public void closeDesbloqueiaValidacaoDuranteLeitura() throws Exception {
        CountDownLatch pedidoRecebido = new CountDownLatch(1);
        ExecutorService solicitacoes = executor();
        try (ServidorTeste servidor = new ServidorTeste()) {
            Future<Void> atendimento = servidor.atender((conexao, entrada, saida) -> {
                assertTrue(entrada.readObject() instanceof ValidarCPFRequest);
                pedidoRecebido.countDown();
                assertThrows(EOFException.class, entrada::readObject);
            });
            Cliente cliente = new Cliente(servidor.host(), servidor.porta());
            try {
                Future<Exception> falha = solicitacoes.submit(() -> assertThrows(Exception.class,
                        () -> cliente.validarCPF(new ValidarCPFRequest("52998224725"))));
                assertTrue(pedidoRecebido.await(3, TimeUnit.SECONDS));
                cliente.close();
                assertNotNull(falha.get(3, TimeUnit.SECONDS));
                atendimento.get(3, TimeUnit.SECONDS);
            } finally {
                cliente.close();
            }
        } finally {
            solicitacoes.shutdownNow();
        }
    }

    @Test(timeout = 15000)
    public void mantemConexaoOciosaPorMaisDeTresSegundosSemReconectar() throws Exception {
        try (ServidorTeste servidor = new ServidorTeste()) {
            Future<Void> atendimento = servidor.atender((conexao, entrada, saida) -> {
                for (String cpf : new String[]{"52998224725", "11111111111"}) {
                    ValidarCPFRequest pedido = (ValidarCPFRequest) entrada.readObject();
                    assertEquals(cpf, pedido.getCPF());
                    saida.writeObject(new ValidarCPFResponse(cpf, cpf.equals("52998224725"), "Resposta"));
                    saida.flush();
                }
                assertTrue(entrada.readObject() instanceof PedidoParaSair);
            });
            Cliente cliente = servidor.cliente();
            try {
                TimeUnit.MILLISECONDS.sleep(3300);
                assertTrue(cliente.validarCPF(new ValidarCPFRequest("52998224725")).isValido());
                assertFalse(cliente.validarCPF(new ValidarCPFRequest("11111111111")).isValido());
                assertEquals(1, servidor.conexoes.size());
            } finally {
                cliente.close();
            }
            atendimento.get(3, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 15000)
    public void conexaoAbertaSemRespostaContinuaAguardando() throws Exception {
        CountDownLatch recebeu = new CountDownLatch(1), responder = new CountDownLatch(1);
        ExecutorService solicitacoes = executor();
        try (ServidorTeste servidor = new ServidorTeste()) {
            Future<Void> atendimento = servidor.atender((conexao, entrada, saida) -> {
                ValidarCPFRequest pedido = (ValidarCPFRequest) entrada.readObject();
                recebeu.countDown();
                assertTrue(responder.await(8, TimeUnit.SECONDS));
                saida.writeObject(new ValidarCPFResponse(pedido.getCPF(), true, "OK"));
                saida.flush();
                assertTrue(entrada.readObject() instanceof PedidoParaSair);
            });
            Cliente cliente = servidor.cliente();
            try {
                Future<ValidarCPFResponse> resposta = solicitacoes.submit(
                        () -> cliente.validarCPF(new ValidarCPFRequest("52998224725")));
                assertTrue(recebeu.await(3, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> resposta.get(3300, TimeUnit.MILLISECONDS));
                responder.countDown();
                assertTrue(resposta.get(3, TimeUnit.SECONDS).isValido());
            } finally {
                responder.countDown();
                cliente.close();
            }
            atendimento.get(3, TimeUnit.SECONDS);
        } finally {
            responder.countDown();
            solicitacoes.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void fechamentoRemotoDuranteInicializacaoLiberaConexao() throws Exception {
        ExecutorService executor = executor();
        try (ServerSocket servidor = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            servidor.setSoTimeout(3000);
            Future<Void> atendimento = executor.submit(() -> {
                try (Socket conexao = servidor.accept()) {
                    conexao.setSoTimeout(3000);
                    assertEquals(4, conexao.getInputStream().readNBytes(4).length);
                    conexao.shutdownOutput();
                    assertEquals(-1, conexao.getInputStream().read());
                }
                return null;
            });
            assertThrows(Exception.class,
                    () -> new Cliente(servidor.getInetAddress().getHostAddress(), servidor.getLocalPort()));
            atendimento.get(3, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test(timeout = 15000)
    public void closeDesbloqueiaEnvioRealmenteBloqueadoNoSocket() throws Exception {
        CountDownLatch pronto = new CountDownLatch(1), liberar = new CountDownLatch(1), escrevendo = new CountDownLatch(1);
        AtomicReference<Thread> envio = new AtomicReference<>();
        ExecutorService solicitacoes = executor();
        try (ServidorTeste servidor = new ServidorTeste()) {
            servidor.servidor.setReceiveBufferSize(1024);
            Future<Void> atendimento = servidor.atender((conexao, entrada, saida) -> {
                conexao.setReceiveBufferSize(1024);
                pronto.countDown();
                assertTrue(liberar.await(10, TimeUnit.SECONDS));
            });
            Cliente cliente = servidor.cliente();
            try {
                assertTrue(pronto.await(3, TimeUnit.SECONDS));
                Future<Exception> validacao = solicitacoes.submit(() -> {
                    envio.set(Thread.currentThread());
                    return assertThrows(Exception.class, () -> cliente.validarCPF(new PedidoComCargaDeTeste(escrevendo)));
                });
                assertTrue(escrevendo.await(3, TimeUnit.SECONDS));
                esperarEscritaBloqueada(envio.get(), validacao);
                Future<?> fechamento = solicitacoes.submit(cliente::close);
                fechamento.get(3, TimeUnit.SECONDS);
                assertNotNull(validacao.get(3, TimeUnit.SECONDS));
                cliente.close();
            } finally {
                cliente.close();
                liberar.countDown();
            }
            atendimento.get(3, TimeUnit.SECONDS);
        } finally {
            liberar.countDown();
            solicitacoes.shutdownNow();
        }
    }

    private static void esperarEscritaBloqueada(Thread envio, Future<?> validacao) throws Exception {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < limite) {
            assertFalse("A validação terminou antes do fechamento", validacao.isDone());
            if (emEscritaDeSocket(envio)) {
                assertThrows(TimeoutException.class, () -> validacao.get(100, TimeUnit.MILLISECONDS));
                if (emEscritaDeSocket(envio)) return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        fail("O teste não conseguiu comprovar o bloqueio de escrita no socket");
    }

    private static boolean emEscritaDeSocket(Thread envio) {
        for (StackTraceElement frame : envio.getStackTrace()) {
            String tipo = frame.getClassName(), metodo = frame.getMethodName();
            if ((tipo.endsWith("SocketDispatcher") && metodo.equals("write0"))
                    || (tipo.endsWith("SocketOutputStream") && metodo.equals("socketWrite0"))
                    || (tipo.endsWith("NioSocketImpl") && metodo.equals("implWrite"))) return true;
        }
        return false;
    }

    private static final class PedidoComCargaDeTeste extends ValidarCPFRequest {
        private final byte[] carga = new byte[4 * 1024 * 1024];
        private final transient CountDownLatch escrevendo;

        private PedidoComCargaDeTeste(CountDownLatch escrevendo) throws Exception {
            super("52998224725");
            this.escrevendo = escrevendo;
        }

        private void writeObject(ObjectOutputStream saida) throws IOException {
            // Carga exclusiva do teste para encher os buffers de uma conexão que não lê.
            escrevendo.countDown();
            saida.defaultWriteObject();
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
                    conexao.setSoTimeout(10000);
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
