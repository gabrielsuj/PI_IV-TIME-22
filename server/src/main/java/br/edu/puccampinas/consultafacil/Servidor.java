package br.edu.puccampinas.consultafacil;

import br.edu.puccampinas.consultafacil.connection.AceitadoraDeConexao;
import br.edu.puccampinas.consultafacil.core.Teclado;
import br.edu.puccampinas.consultafacil.protocol.ComunicadoDeDesligamento;
import br.edu.puccampinas.consultafacil.protocol.Parceiro;

import java.util.*;

public class Servidor {
	public static final String PORTA_PADRAO = "3000";
	
    public static void main(String[] args) {
        if(args.length>1) {
            System.err.println ("Uso esperado: java Servidor [PORTA]\n");
            return;
        }

        String porta = Servidor.PORTA_PADRAO;
        
        if(args.length==1) porta = args[0];

        ArrayList<Parceiro> usuarios = new ArrayList<>();

        AceitadoraDeConexao aceitadoraDeConexao = null;
        try {
            aceitadoraDeConexao = new AceitadoraDeConexao(porta, usuarios);
            aceitadoraDeConexao.start();
        } catch(Exception erro) {
            System.err.println ("Escolha uma porta apropriada e liberada para uso!\n");
            return;
        }

        for(;;) {
            System.out.println("O servidor esta ativo! Para desativa-lo,");
            System.out.println("use o comando \"desativar\"\n");
            System.out.print("> ");

            String comando = null;
            try {
                comando = Teclado.getUmString();
            } catch(Exception ignored) {}

            if(comando.toLowerCase().equals("desativar")) {
                synchronized(usuarios) {
					ComunicadoDeDesligamento comunicadoDeDesligamento = new ComunicadoDeDesligamento();
                    
                    for(Parceiro usuario:usuarios) {
                        try {
                            usuario.receba(comunicadoDeDesligamento);
                            usuario.adeus();
                        } catch (Exception ignored) {}
                    }
                }

                System.out.println("O servidor foi desativado!\n");
                System.exit(0);
            } else System.err.println("Comando invalido!\n");
        }
    }
}
