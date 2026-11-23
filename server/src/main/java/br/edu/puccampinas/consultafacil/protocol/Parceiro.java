// Davi José Bertuolo Vitoreti - 25004168

package br.edu.puccampinas.consultafacil.protocol;

import java.io.*;
import java.net.*;
import java.util.concurrent.Semaphore;

public class Parceiro {
    private final Socket conexao;
    private final ObjectInputStream receptor;
    private final ObjectOutputStream transmissor;
    
    private Comunicado proximoComunicado=null;

    private final Semaphore mutEx = new Semaphore(1,true);
    private final Object estado = new Object();
    private boolean encerrado;
    private boolean operacaoEmAndamento;
    private Exception falha;

    public Parceiro(Socket conexao, ObjectInputStream  receptor, ObjectOutputStream transmissor) throws Exception {
        if (conexao==null) throw new Exception ("Conexao ausente");
        if (receptor==null) throw new Exception ("Receptor ausente");
        if (transmissor==null) throw new Exception ("Transmissor ausente");

        this.conexao = conexao;
        this.receptor = receptor;
        this.transmissor = transmissor;
    }

    public synchronized void receba(Comunicado x) throws Exception {
        try {
            this.transmissor.writeObject (x);
            this.transmissor.flush       ();
        } catch(IOException erro) {
            throw new Exception ("Erro de transmissao");
        }
    }

    public Comunicado espie() throws Exception {
        this.mutEx.acquireUninterruptibly();
        try {
            return proximo();
        } catch(Exception erro) {
            throw new Exception ("Erro de recepcao", erro);
        } finally {
            this.mutEx.release();
        }
    }

    public Comunicado envie() throws Exception {
        this.mutEx.acquireUninterruptibly();
        try {
            Comunicado ret         = proximo();
            this.proximoComunicado = null;
            return ret;
        } catch(Exception erro) {
            throw new Exception ("Erro de recepcao", erro);
        } finally {
            this.mutEx.release();
        }
    }

    private Comunicado proximo() throws Exception {
        if (conexao.isClosed()) throw new IOException("Conexao encerrada");
        if (proximoComunicado == null) proximoComunicado = (Comunicado) receptor.readObject();
        return proximoComunicado;
    }

    public void iniciarOperacao() throws Exception {
        synchronized (estado) {
            if (encerrado) throw new IOException("Conexao encerrada", falha);
            operacaoEmAndamento = true;
        }
    }

    public void concluirOperacao() throws Exception {
        synchronized (estado) {
            if (encerrado) throw new IOException("Conexao encerrada", falha);
            operacaoEmAndamento = false;
        }
    }

    public Exception indisponivel(Exception erro) {
        Exception causa;
        synchronized (estado) {
            if (!encerrado) falha = erro;
            encerrado = true;
            operacaoEmAndamento = false;
            causa = falha == null ? erro : falha;
        }
        try { adeus(); } catch (Exception ignored) {}
        return causa;
    }

    public void encerrar() throws Exception {
        boolean avisar;
        synchronized (estado) {
            avisar = !encerrado && !operacaoEmAndamento;
            encerrado = true;
            operacaoEmAndamento = false;
        }
        try {
            // Com I/O em andamento, fecha direto para não disputar o monitor de escrita.
            if (avisar) receba(new PedidoParaSair());
        } finally {
            adeus();
        }
    }

    public void adeus() throws Exception {
        synchronized (estado) {
            encerrado = true;
            operacaoEmAndamento = false;
        }
        try {
            // Fechar o socket também desbloqueia uma leitura em andamento nos streams.
            this.conexao    .close();
        } catch(Exception erro) {
            throw new Exception ("Erro de desconexao", erro);
        }
    }
}
