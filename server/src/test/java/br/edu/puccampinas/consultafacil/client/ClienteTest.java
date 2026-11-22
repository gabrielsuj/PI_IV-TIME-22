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
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class ClienteTest {
    @After
    public void naoDeixaTratadorasAtivas() throws Exception {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread instanceof TratadoraDeComunicadoDeDesligamento || thread.getName().startsWith("cpf-timeout-")) {
                thread.join(1000);
                assertFalse("Thread do cliente permaneceu ativa: " + thread.getName(), thread.isAlive());
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
        conferirFechamentoDuranteEspera(0);
    }

    @Test(timeout = 10000)
    public void closeDuranteEsperaCancelaPrazoPositivo() throws Exception {
        conferirFechamentoDuranteEspera(2000);
    }

    private void conferirFechamentoDuranteEspera(int timeout) throws Exception {
        CountDownLatch pedidoRecebido = new CountDownLatch(1);
        ExecutorService solicitacoes = executor();
        try (ServidorTeste servidor = new ServidorTeste()) {
            Future<Void> atendimento = servidor.atender((conexao, entrada, saida) -> {
                assertTrue(entrada.readObject() instanceof ValidarCPFRequest);
                pedidoRecebido.countDown();
                assertTrue(entrada.readObject() instanceof PedidoParaSair);
                assertThrows(EOFException.class, entrada::readObject);
            });
            try (Cliente cliente = new Cliente(servidor.host(), servidor.porta(), 3000, timeout)) {
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
                Exception seguinte = assertThrows(Exception.class,
                        () -> cliente.validarCPF(new ValidarCPFRequest("11111111111")));
                assertTrue(causaRaiz(seguinte) instanceof SocketTimeoutException);
            } finally {
                liberar.countDown();
            }
            atendimento.get(3, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 10000)
    public void mantemConexaoSaudavelOciosaEValidaDoisCpfsSemReconectar() throws Exception {
        int prazo = 400;
        try (ServidorTeste servidor = new ServidorTeste()) {
            Future<Void> atendimento = servidor.atender((conexao, entrada, saida) -> {
                responder(entrada, saida, "52998224725");
                responder(entrada, saida, "11111111111");
                assertTrue(entrada.readObject() instanceof PedidoParaSair);
            });
            try (Cliente cliente = new Cliente(servidor.host(), servidor.porta(), 3000, prazo)) {
                esperarAlemDoPrazo(prazo);
                assertTrue(cliente.validarCPF(new ValidarCPFRequest("52998224725")).isValido());
                assertFalse(cliente.validarCPF(new ValidarCPFRequest("11111111111")).isValido());
                assertEquals(1, servidor.conexoes.size());
            }
            atendimento.get(3, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 10000)
    public void recebePrazoCompletoParaRespostaAposOciosidade() throws Exception {
        int prazo = 800;
        CountDownLatch recebeu = new CountDownLatch(1), responder = new CountDownLatch(1);
        ExecutorService solicitacoes = executor();
        try (ServidorTeste servidor = new ServidorTeste()) {
            Future<Void> atendimento = servidor.atender((conexao, entrada, saida) -> {
                ValidarCPFRequest pedido = (ValidarCPFRequest) entrada.readObject();
                recebeu.countDown();
                assertTrue(responder.await(3, TimeUnit.SECONDS));
                saida.writeObject(new ValidarCPFResponse(pedido.getCPF(), true, "OK"));
                saida.flush();
                assertTrue(entrada.readObject() instanceof PedidoParaSair);
            });
            try (Cliente cliente = new Cliente(servidor.host(), servidor.porta(), 3000, prazo)) {
                esperarAlemDoPrazo(prazo);
                Future<ValidarCPFResponse> resposta = solicitacoes.submit(
                        () -> cliente.validarCPF(new ValidarCPFRequest("52998224725")));
                assertTrue(recebeu.await(3, TimeUnit.SECONDS));
                TimeUnit.MILLISECONDS.sleep(250);
                responder.countDown();
                assertEquals("52998224725", resposta.get(3, TimeUnit.SECONDS).getCPF());
                assertEquals(1, servidor.conexoes.size());
            }
            atendimento.get(3, TimeUnit.SECONDS);
        } finally {
            responder.countDown();
            solicitacoes.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void prazoConcluidoNaoFechaConexaoNemAfetaPedidoSeguinte() throws Exception {
        int prazo = 400;
        try (ServidorTeste servidor = new ServidorTeste()) {
            Future<Void> atendimento = servidor.atender((conexao, entrada, saida) -> {
                responder(entrada, saida, "52998224725");
                responder(entrada, saida, "11111111111");
                assertTrue(entrada.readObject() instanceof PedidoParaSair);
            });
            try (Cliente cliente = new Cliente(servidor.host(), servidor.porta(), 3000, prazo)) {
                assertTrue(cliente.validarCPF(new ValidarCPFRequest("52998224725")).isValido());
                esperarAlemDoPrazo(prazo);
                assertFalse(cliente.validarCPF(new ValidarCPFRequest("11111111111")).isValido());
            }
            atendimento.get(3, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 10000)
    public void respostaTardiaDePedidoExpiradoNaoPodeSerReutilizada() throws Exception {
        CountDownLatch tentouResponder = new CountDownLatch(1);
        try (ServidorTeste servidor = new ServidorTeste()) {
            Future<Void> atendimento = servidor.atender((conexao, entrada, saida) -> {
                ValidarCPFRequest pedido = (ValidarCPFRequest) entrada.readObject();
                assertThrows(EOFException.class, entrada::readObject);
                try {
                    saida.writeObject(new ValidarCPFResponse(pedido.getCPF(), true, "Resposta tardia"));
                    saida.flush();
                } catch (java.io.IOException encerrada) {
                    // O cliente já encerrou a conexão cujo prazo venceu.
                } finally {
                    tentouResponder.countDown();
                }
            });
            try (Cliente cliente = new Cliente(servidor.host(), servidor.porta(), 3000, 200)) {
                Exception erro = assertThrows(Exception.class,
                        () -> cliente.validarCPF(new ValidarCPFRequest("52998224725")));
                assertTrue(causaRaiz(erro) instanceof SocketTimeoutException);
                assertTrue(tentouResponder.await(3, TimeUnit.SECONDS));
                try {
                    atendimento.get(3, TimeUnit.SECONDS);
                } catch (ExecutionException erroNoFechamento) {
                    assertTrue(causaRaiz(erroNoFechamento) instanceof SocketException);
                }
                Exception seguinte = assertThrows(Exception.class,
                        () -> cliente.validarCPF(new ValidarCPFRequest("11111111111")));
                assertTrue(causaRaiz(seguinte) instanceof SocketTimeoutException);
                assertEquals(1, servidor.conexoes.size());
                cliente.close();
                cliente.close();
            }
        }
    }

    @Test(timeout = 10000)
    public void prazoDoSegundoPedidoComecaDepoisDeObterExclusao() throws Exception {
        CountDownLatch recebeuPrimeiro = new CountDownLatch(1), liberarPrimeiro = new CountDownLatch(1);
        CountDownLatch recebeuSegundo = new CountDownLatch(1), liberarSegundo = new CountDownLatch(1);
        CountDownLatch iniciouSegundo = new CountDownLatch(1);
        ExecutorService solicitacoes = executor();
        try (ServidorTeste servidor = new ServidorTeste()) {
            Future<Void> atendimento = servidor.atender((conexao, entrada, saida) -> {
                for (int i = 0; i < 2; i++) {
                    ValidarCPFRequest pedido = (ValidarCPFRequest) entrada.readObject();
                    (i == 0 ? recebeuPrimeiro : recebeuSegundo).countDown();
                    assertTrue((i == 0 ? liberarPrimeiro : liberarSegundo).await(3, TimeUnit.SECONDS));
                    saida.writeObject(new ValidarCPFResponse(pedido.getCPF(), i == 0, "Resposta"));
                    saida.flush();
                }
                assertTrue(entrada.readObject() instanceof PedidoParaSair);
            });
            try (Cliente cliente = new Cliente(servidor.host(), servidor.porta(), 3000, 1000)) {
                Future<ValidarCPFResponse> primeira = solicitacoes.submit(
                        () -> cliente.validarCPF(new ValidarCPFRequest("52998224725")));
                assertTrue(recebeuPrimeiro.await(3, TimeUnit.SECONDS));
                Future<ValidarCPFResponse> segunda = solicitacoes.submit(() -> {
                    iniciouSegundo.countDown();
                    return cliente.validarCPF(new ValidarCPFRequest("11111111111"));
                });
                assertTrue(iniciouSegundo.await(3, TimeUnit.SECONDS));
                TimeUnit.MILLISECONDS.sleep(600);
                liberarPrimeiro.countDown();
                assertTrue(primeira.get(3, TimeUnit.SECONDS).isValido());
                assertTrue(recebeuSegundo.await(3, TimeUnit.SECONDS));
                TimeUnit.MILLISECONDS.sleep(600);
                liberarSegundo.countDown();
                assertEquals("11111111111", segunda.get(3, TimeUnit.SECONDS).getCPF());
            }
            atendimento.get(3, TimeUnit.SECONDS);
        } finally {
            liberarPrimeiro.countDown();
            liberarSegundo.countDown();
            solicitacoes.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void preservaTimeoutNaInicializacaoDoStreamRemoto() throws Exception {
        ExecutorService executor = executor();
        try (ServerSocket servidor = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            servidor.setSoTimeout(3000);
            Future<Void> atendimento = executor.submit(() -> {
                try (Socket conexao = servidor.accept()) {
                    conexao.setSoTimeout(3000);
                    assertEquals(4, conexao.getInputStream().readNBytes(4).length);
                    assertEquals(-1, conexao.getInputStream().read());
                }
                return null;
            });
            Exception erro = assertThrows(Exception.class,
                    () -> new Cliente(servidor.getInetAddress().getHostAddress(), servidor.getLocalPort(), 3000, 200));
            assertTrue(causaRaiz(erro) instanceof SocketTimeoutException);
            atendimento.get(3, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void rejeitaTimeoutsNegativos() throws Exception {
        try (ServidorTeste servidor = new ServidorTeste()) {
            Exception leitura = assertThrows(Exception.class, () -> new Cliente(servidor.host(), servidor.porta(), 3000, -1));
            Exception conexao = assertThrows(Exception.class, () -> new Cliente(servidor.host(), servidor.porta(), -1, 3000));
            assertTrue(causaRaiz(leitura) instanceof IllegalArgumentException);
            assertTrue(causaRaiz(conexao) instanceof IllegalArgumentException);
        }
    }

    private static void responder(ObjectInputStream entrada, ObjectOutputStream saida, String cpf) throws Exception {
        ValidarCPFRequest pedido = (ValidarCPFRequest) entrada.readObject();
        assertEquals(cpf, pedido.getCPF());
        saida.writeObject(new ValidarCPFResponse(cpf, cpf.equals("52998224725"), "Resposta"));
        saida.flush();
    }

    private static void esperarAlemDoPrazo(int prazo) throws InterruptedException {
        // A espera real demonstra que a ociosidade ultrapassou o prazo configurado.
        TimeUnit.MILLISECONDS.sleep(2L * prazo + 100);
    }

    private static Throwable causaRaiz(Throwable erro) {
        while (erro.getCause() != null) erro = erro.getCause();
        return erro;
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
