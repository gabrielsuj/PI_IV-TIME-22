package br.edu.puccampinas.consultafacil.connection;

import br.edu.puccampinas.consultafacil.protocol.Parceiro;

import java.net.*;
import java.util.*;

public class AceitadoraDeConexao extends Thread {
    private final ServerSocket pedido;
    private final ArrayList<Parceiro> usuarios;

    public AceitadoraDeConexao(String porta, ArrayList<Parceiro> usuarios) throws Exception {
        if(porta==null) throw new Exception ("Porta ausente");

        try {
            this.pedido = new ServerSocket (Integer.parseInt(porta));
        } catch(Exception  erro) {
            throw new Exception ("Porta invalida");
        }

        if(usuarios==null) throw new Exception ("Usuarios ausentes");

        this.usuarios = usuarios;
    }

    public void run() {
        for(;;) {
            Socket conexao=null;
            try {
                conexao = this.pedido.accept();
            } catch(Exception erro) {
                continue;
            }

            SupervisoraDeConexao supervisoraDeConexao=null;
            try {
                supervisoraDeConexao =
                new SupervisoraDeConexao (conexao, usuarios);
            } catch(Exception ignored) {} // sei que passei parametros corretos para o construtor
            supervisoraDeConexao.start();
        }
    }
}
