// Davi José Bertuolo Vitoreti - 25004168

package br.edu.puccampinas.consultafacil.connection;

import br.edu.puccampinas.consultafacil.protocol.Parceiro;

import java.net.*;
import java.util.*;

public class AceitadoraDeConexao extends Thread {
    private final ServerSocket pedido;
    private final ArrayList<Parceiro> usuarios;

    public AceitadoraDeConexao(String porta, ArrayList<Parceiro> usuarios) throws Exception {
        if(porta==null) throw new Exception ("Porta ausente");
        if(usuarios==null) throw new Exception ("Usuarios ausentes");

        try {
            this.pedido = new ServerSocket (Integer.parseInt(porta));
        } catch(Exception  erro) {
            throw new Exception ("Porta invalida");
        }

        this.usuarios = usuarios;
    }

    public void run() {
        for(;;) {
            Socket conexao;
            try {
                conexao = this.pedido.accept();
            } catch(Exception erro) {
                continue;
            }

            try {
                SupervisoraDeConexao supervisoraDeConexao = new SupervisoraDeConexao(conexao, usuarios);
                supervisoraDeConexao.start();
            } catch(Exception erro) {
                try { conexao.close(); } catch(Exception ignored) {}
            }
        }
    }
}
