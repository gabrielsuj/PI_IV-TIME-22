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

    
    public Cliente(String host, int porta) throws Exception {
        this(host, porta, 3000, 3000);
    }

    public Cliente(String host, int porta, int timeoutConexao, int timeoutLeitura) throws Exception {
        Socket conexao = new Socket();

        try {
            conexao.connect(new InetSocketAddress(host, porta), timeoutConexao);
            conexao.setSoTimeout(timeoutLeitura);
        } catch(Exception erro) {
            try { conexao.close(); } catch(Exception ignored) {}
            throw new Exception("Erro ao conectar com o servidor", erro);
        }

        try {
            this.transmissor = new ObjectOutputStream(conexao.getOutputStream());
            this.receptor    = new ObjectInputStream(conexao.getInputStream());
            this.servidor    = new Parceiro(conexao, receptor, transmissor);
            this.tratadoraDeComunicadoDeDesligamento = new TratadoraDeComunicadoDeDesligamento(servidor, this::indisponivel);
            this.conexao = conexao;
            this.tratadoraDeComunicadoDeDesligamento.start();
        } catch(Exception erro) {
            try { conexao.close(); } catch(Exception ignored) {}
            throw erro;
        }
    }


    public Cliente() throws Exception { this(HOST_PADRAO, PORTA_PADRAO); }

    public synchronized ValidarCPFResponse validarCPF(ValidarCPFRequest cpf) throws Exception {
        try {
            if (encerrado.get()) throw new IOException("Conexao encerrada", falha);
            servidor.receba(cpf);
            Comunicado resposta = servidor.espie();
            if (resposta instanceof ComunicadoDeDesligamento)
                throw new IOException("Servidor de CPF desligado");
            if (!(resposta instanceof ValidarCPFResponse))
                throw new IOException("Resposta inesperada do servidor de CPF");
            return (ValidarCPFResponse) servidor.envie();
        } catch (Exception erro) {
            indisponivel(erro);
            throw new Exception("Erro de comunicacao com o servidor de CPF", falha == null ? erro : falha);
        }
    }

    private void indisponivel(Exception erro) {
        if (encerrado.compareAndSet(false, true)) {
            falha = erro;
            encerrarConexao();
        }
    }

    private void encerrarConexao() {
        try { servidor.adeus(); } catch (Exception ignored) {}
        tratadoraDeComunicadoDeDesligamento.interrupt();
    }

    @Override
    public void close() {
        if (encerrado.compareAndSet(false, true)) {
            try { servidor.receba(new PedidoParaSair()); } catch (Exception ignored) {}
            finally { encerrarConexao(); }
        }
        if (Thread.currentThread() != tratadoraDeComunicadoDeDesligamento) {
            try { tratadoraDeComunicadoDeDesligamento.join(); }
            catch (InterruptedException erro) { Thread.currentThread().interrupt(); }
        }
    }
}
