// Davi José Bertuolo Vitoreti - 25004168

package br.edu.puccampinas.consultafacil.client;

import br.edu.puccampinas.consultafacil.connection.TratadoraDeComunicadoDeDesligamento;
import br.edu.puccampinas.consultafacil.handler.ValidarCPFRequest;
import br.edu.puccampinas.consultafacil.handler.ValidarCPFResponse;
import br.edu.puccampinas.consultafacil.protocol.Comunicado;
import br.edu.puccampinas.consultafacil.protocol.ComunicadoDeDesligamento;
import br.edu.puccampinas.consultafacil.protocol.Parceiro;

import java.net.*;
import java.io.*;

public class Cliente {
	public static final String HOST_PADRAO  = "localhost";
	public static final int    PORTA_PADRAO = 3001;

    private final Socket conexao;
    private final ObjectOutputStream transmissor;
    private final ObjectInputStream receptor;

    private final Parceiro servidor;

    private final TratadoraDeComunicadoDeDesligamento tratadoraDeComunicadoDeDesligamento;
    
    public Cliente(String host, int porta) throws Exception {
        Socket conexao = new Socket(host, porta);
        try {
            this.transmissor = new ObjectOutputStream(conexao.getOutputStream());
            this.receptor    = new ObjectInputStream(conexao.getInputStream());
            this.servidor    = new Parceiro(conexao, receptor, transmissor);
            this.tratadoraDeComunicadoDeDesligamento = new TratadoraDeComunicadoDeDesligamento(servidor);
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
            servidor.iniciarOperacao();
            servidor.receba(cpf);
            Comunicado resposta = servidor.espie();
            if (resposta instanceof ComunicadoDeDesligamento)
                throw new IOException("Servidor de CPF desligado");
            if (!(resposta instanceof ValidarCPFResponse))
                throw new IOException("Resposta inesperada do servidor de CPF");
            ValidarCPFResponse resultado = (ValidarCPFResponse) servidor.envie();
            servidor.concluirOperacao();
            return resultado;
        } catch (Exception erro) {
            throw new Exception("Erro de comunicacao com o servidor de CPF", servidor.indisponivel(erro));
        }
    }

    public void close() {
        try { servidor.encerrar(); } catch (Exception ignored) {}
    }
}
