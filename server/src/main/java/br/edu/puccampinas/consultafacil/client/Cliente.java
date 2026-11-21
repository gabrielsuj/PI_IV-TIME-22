// Davi José Bertuolo Vitoreti - 25004168

package br.edu.puccampinas.consultafacil.client;

import br.edu.puccampinas.consultafacil.connection.TratadoraDeComunicadoDeDesligamento;
import br.edu.puccampinas.consultafacil.handler.ValidarCPFRequest;
import br.edu.puccampinas.consultafacil.handler.ValidarCPFResponse;
import br.edu.puccampinas.consultafacil.protocol.Comunicado;
import br.edu.puccampinas.consultafacil.protocol.Parceiro;
import br.edu.puccampinas.consultafacil.protocol.PedidoParaSair;

import java.net.*;
import java.io.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Cliente implements AutoCloseable {
	public static final String HOST_PADRAO  = "localhost";
	public static final int    PORTA_PADRAO = 3000;

    private final Socket conexao;
    private final ObjectOutputStream transmissor;
    private final ObjectInputStream receptor;

    private final Parceiro servidor;

    private final TratadoraDeComunicadoDeDesligamento tratadoraDeComunicadoDeDesligamento;
    private final BlockingQueue<Comunicado> respostas = new LinkedBlockingQueue<>();

    public Cliente(String host, int porta) throws Exception {
        try {
            this.conexao = new Socket(host, porta);
        } catch(Exception erro) {
            throw new Exception("Host e/ou porta não foram informandos!");
        }

        this.transmissor = new ObjectOutputStream(conexao.getOutputStream());
        this.receptor    = new ObjectInputStream(conexao.getInputStream());
        this.servidor    = new Parceiro(conexao, receptor, transmissor);

        this.tratadoraDeComunicadoDeDesligamento = new TratadoraDeComunicadoDeDesligamento(servidor, respostas);
    }

    public Cliente() throws Exception { this(HOST_PADRAO, PORTA_PADRAO); }

    public synchronized ValidarCPFResponse validarCPF(ValidarCPFRequest cpf) throws Exception {
        servidor.receba(cpf);

        Comunicado resposta = null;
        do {
            resposta = servidor.envie();
        } while(!(resposta instanceof ValidarCPFResponse));

        return (ValidarCPFResponse)(resposta);
    }

    @Override
    public synchronized void close() {
        try { servidor.receba(new PedidoParaSair()); } catch (Exception ignored) {}
        try { servidor.adeus(); } catch (Exception ignored) {}
    }
}
