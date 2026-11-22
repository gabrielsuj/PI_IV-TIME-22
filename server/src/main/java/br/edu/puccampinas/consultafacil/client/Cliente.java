// Davi José Bertuolo Vitoreti - 25004168

package br.edu.puccampinas.consultafacil.client;

import br.edu.puccampinas.consultafacil.connection.TratadoraDeComunicadoDeDesligamento;
import br.edu.puccampinas.consultafacil.handler.ValidarCPFRequest;
import br.edu.puccampinas.consultafacil.handler.ValidarCPFResponse;
import br.edu.puccampinas.consultafacil.protocol.Comunicado;
import br.edu.puccampinas.consultafacil.protocol.ComunicadoDeDesligamento;
import br.edu.puccampinas.consultafacil.protocol.Parceiro;
import br.edu.puccampinas.consultafacil.protocol.PedidoParaSair;

import java.net.*;
import java.io.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Cliente implements AutoCloseable {
	public static final String HOST_PADRAO  = "localhost";
	public static final int    PORTA_PADRAO = 3001;

    private final Socket conexao;
    private final ObjectOutputStream transmissor;
    private final ObjectInputStream receptor;

    private final Parceiro servidor;

    private final TratadoraDeComunicadoDeDesligamento tratadoraDeComunicadoDeDesligamento;
    private final AtomicBoolean encerrado = new AtomicBoolean();
    private volatile Exception falha;
    private final int timeoutLeitura;
    private final ScheduledThreadPoolExecutor temporizador;
    private final Object controlePrazo = new Object();
    private Pedido pedidoAtual;

    private static final class Pedido {
        private ScheduledFuture<?> prazo;
    }

    
    public Cliente(String host, int porta) throws Exception {
        this(host, porta, 3000, 3000);
    }

    public Cliente(String host, int porta, int timeoutConexao, int timeoutLeitura) throws Exception {
        Socket conexao = new Socket();
        this.timeoutLeitura = timeoutLeitura;

        try {
            conexao.connect(new InetSocketAddress(host, porta), timeoutConexao);
            conexao.setSoTimeout(timeoutLeitura);
        } catch(Exception erro) {
            try { conexao.close(); } catch(Exception ignored) {}
            throw new Exception("Erro ao conectar com o servidor", erro);
        }

        ScheduledThreadPoolExecutor executorPrazo = null;
        try {
            this.transmissor = new ObjectOutputStream(conexao.getOutputStream());
            this.receptor    = new ObjectInputStream(conexao.getInputStream());
            conexao.setSoTimeout(0);
            if (timeoutLeitura > 0) {
                executorPrazo = new ScheduledThreadPoolExecutor(1, tarefa -> {
                    Thread thread = new Thread(tarefa, "cpf-timeout-" + conexao.getLocalPort());
                    thread.setDaemon(true);
                    return thread;
                });
                executorPrazo.setRemoveOnCancelPolicy(true);
            }
            this.temporizador = executorPrazo;
            this.servidor    = new Parceiro(conexao, receptor, transmissor);
            this.tratadoraDeComunicadoDeDesligamento = new TratadoraDeComunicadoDeDesligamento(servidor, this::indisponivel);
            this.conexao = conexao;
            this.tratadoraDeComunicadoDeDesligamento.start();
        } catch(Exception erro) {
            if (executorPrazo != null) executorPrazo.shutdownNow();
            try { conexao.close(); } catch(Exception ignored) {}
            throw erro;
        }
    }


    public Cliente() throws Exception { this(HOST_PADRAO, PORTA_PADRAO); }

    public synchronized ValidarCPFResponse validarCPF(ValidarCPFRequest cpf) throws Exception {
        Pedido pedido = null;
        try {
            synchronized (controlePrazo) {
                if (encerrado.get()) throw new IOException("Conexao encerrada", falha);
                pedidoAtual = pedido = new Pedido();
                Pedido atual = pedido;
                // O prazo pertence a este pedido e inclui envio e espera pela resposta.
                if (temporizador != null) pedido.prazo = temporizador.schedule(
                        () -> indisponivel(new SocketTimeoutException("Prazo da validacao de CPF esgotado"), atual),
                        timeoutLeitura, TimeUnit.MILLISECONDS);
            }
            servidor.receba(cpf);
            Comunicado resposta = servidor.espie();
            if (resposta instanceof ComunicadoDeDesligamento)
                throw new IOException("Servidor de CPF desligado");
            if (!(resposta instanceof ValidarCPFResponse))
                throw new IOException("Resposta inesperada do servidor de CPF");
            ValidarCPFResponse resultado = (ValidarCPFResponse) servidor.envie();
            synchronized (controlePrazo) {
                if (pedidoAtual != pedido || encerrado.get()) throw new IOException("Conexao encerrada", falha);
                pedidoAtual = null;
            }
            return resultado;
        } catch (Exception erro) {
            indisponivel(erro);
            throw new Exception("Erro de comunicacao com o servidor de CPF", falha == null ? erro : falha);
        } finally {
            synchronized (controlePrazo) {
                if (pedidoAtual == pedido) pedidoAtual = null;
            }
            if (pedido != null && pedido.prazo != null) pedido.prazo.cancel(false);
        }
    }

    private void indisponivel(Exception erro) {
        indisponivel(erro, null);
    }

    private void indisponivel(Exception erro, Pedido esperado) {
        synchronized (controlePrazo) {
            if (esperado != null && pedidoAtual != esperado) return;
            if (!encerrado.compareAndSet(false, true)) return;
            falha = erro;
            pedidoAtual = null;
        }
        encerrarConexao();
    }

    private void encerrarConexao() {
        if (temporizador != null) temporizador.shutdownNow();
        try { servidor.adeus(); } catch (Exception ignored) {}
        tratadoraDeComunicadoDeDesligamento.interrupt();
    }

    @Override
    public void close() {
        boolean fechar;
        synchronized (controlePrazo) {
            fechar = encerrado.compareAndSet(false, true);
            if (fechar) pedidoAtual = null;
        }
        if (fechar) {
            try { servidor.receba(new PedidoParaSair()); } catch (Exception ignored) {}
            finally { encerrarConexao(); }
        }
        if (Thread.currentThread() != tratadoraDeComunicadoDeDesligamento) {
            try { tratadoraDeComunicadoDeDesligamento.join(); }
            catch (InterruptedException erro) { Thread.currentThread().interrupt(); }
        }
    }
}
