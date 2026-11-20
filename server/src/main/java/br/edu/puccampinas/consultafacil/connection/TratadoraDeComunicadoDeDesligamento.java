// Davi José Bertuolo Vitoreti - 25004168

package br.edu.puccampinas.consultafacil.connection;

import br.edu.puccampinas.consultafacil.protocol.Comunicado;
import br.edu.puccampinas.consultafacil.protocol.ComunicadoDeDesligamento;
import br.edu.puccampinas.consultafacil.protocol.Parceiro;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;

public class TratadoraDeComunicadoDeDesligamento extends Thread
{
    private final Parceiro servidor;
    private final BlockingQueue<Comunicado> respostas;

    public TratadoraDeComunicadoDeDesligamento (Parceiro servidor, BlockingQueue<Comunicado> respostas) throws Exception {
        if(servidor == null) throw new Exception ("Porta invalida");
        if(respostas == null) throw new Exception ("Fila ausente");

        this.servidor = servidor;
        this.respostas = respostas;
        setDaemon(true); // Evita que a JVM encerre o processo
    }

    public void run() {
        for(;;) {
			try {
                Comunicado c = servidor.envie(); // bloqueia até chegar algo e CONSOME

				if(c instanceof ComunicadoDeDesligamento) {
					System.out.println("\nO servidor vai ser desligado agora;");
				    System.err.println("volte mais tarde!\n");
				    System.exit(0);
				}

                respostas.put(c); // qualquer outra coisa vai para quem está esperando
			}
			catch(Exception ignored) {}
        }
    }
}
