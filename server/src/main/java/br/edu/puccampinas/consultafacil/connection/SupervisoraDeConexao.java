package br.edu.puccampinas.consultafacil.connection;

import br.edu.puccampinas.consultafacil.handler.ValidarCPFHandler;
import br.edu.puccampinas.consultafacil.handler.ValidarCPFRequest;
import br.edu.puccampinas.consultafacil.protocol.Comunicado;
import br.edu.puccampinas.consultafacil.protocol.Parceiro;
import br.edu.puccampinas.consultafacil.protocol.PedidoParaSair;

import java.io.*;
import java.net.*;
import java.util.*;

public class SupervisoraDeConexao extends Thread {
    private Parceiro usuario;
    private final Socket conexao;
    private final ArrayList<Parceiro> usuarios;

    public SupervisoraDeConexao(Socket conexao, ArrayList<Parceiro> usuarios) throws Exception {
        if (conexao==null) throw new Exception ("Conexao ausente");
        if (usuarios==null) throw new Exception ("Usuarios ausentes");

        this.conexao  = conexao;
        this.usuarios = usuarios;
    }

    public void run() {
        ObjectOutputStream transmissor;
        try {
            transmissor = new ObjectOutputStream(this.conexao.getOutputStream());
        } catch(Exception erro) {
            return;
        }
        
        ObjectInputStream receptor;
        try {
            receptor= new ObjectInputStream(this.conexao.getInputStream());
        } catch(Exception err0) {
            try {
                transmissor.close();
            } catch(Exception ignored) {} // so tentando fechar antes de acabar a thread
            
            return;
        }

        try {
            this.usuario = new Parceiro (this.conexao, receptor, transmissor);
        } catch (Exception ignored) {} // sei que passei os parametros corretos

        try {
            synchronized(this.usuarios) {
                this.usuarios.add (this.usuario);
            }

            for(;;) {
                Comunicado comunicado = this.usuario.envie();

                if(comunicado==null) return;
                else if(comunicado instanceof ValidarCPFRequest) {
                    ValidarCPFRequest request = (ValidarCPFRequest)(comunicado);

                    this.usuario.receba(ValidarCPFHandler.ValidateCPF(request));
                } else if(comunicado instanceof PedidoParaSair) {
                    synchronized(this.usuarios) {
                        this.usuarios.remove(this.usuario);
                    }
                    this.usuario.adeus();
                }
            }
        }
        catch(Exception erro) {
            try {
                transmissor.close();
                receptor.close();
            } catch(Exception ignored) {} // so tentando fechar antes de acabar a thread
        }
    }
}
