// Davi José Bertuolo Vitoreti - 25004168

package br.edu.puccampinas.consultafacil.connection;

import br.edu.puccampinas.consultafacil.protocol.Comunicado;
import br.edu.puccampinas.consultafacil.protocol.ComunicadoDeDesligamento;
import br.edu.puccampinas.consultafacil.protocol.Parceiro;
import br.edu.puccampinas.consultafacil.handler.ValidarCPFResponse;

import java.io.IOException;
import java.util.function.Consumer;

public class TratadoraDeComunicadoDeDesligamento extends Thread
{
    private final Parceiro servidor;
    private final Consumer<Exception> aoEncerrar;

    public TratadoraDeComunicadoDeDesligamento (Parceiro servidor, Consumer<Exception> aoEncerrar) throws Exception {
        if(servidor == null) throw new Exception ("Porta invalida");
        if(aoEncerrar == null) throw new Exception ("Sinalizacao ausente");

        this.servidor = servidor;
        this.aoEncerrar = aoEncerrar;
        setDaemon(true); // Permite que a JVM termine quando restarem apenas threads daemon.
    }

    public void run() {
        try {
            while (!isInterrupted()) {
                Comunicado c = servidor.espie();
                if (c instanceof ComunicadoDeDesligamento)
                    throw new IOException("Servidor de CPF desligado");
                if (!(c instanceof ValidarCPFResponse))
                    throw new IOException("Resposta inesperada do servidor de CPF");
                // A resposta pertence à validação; aguarda seu consumo sem repetir a leitura.
                servidor.aguardeConsumo(c);
            }
        } catch (Exception erro) {
            aoEncerrar.accept(erro);
        }
    }
}
