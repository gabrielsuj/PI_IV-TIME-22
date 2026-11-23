// Davi José Bertuolo Vitoreti - 25004168

package br.edu.puccampinas.consultafacil.connection;

import br.edu.puccampinas.consultafacil.protocol.Comunicado;
import br.edu.puccampinas.consultafacil.protocol.ComunicadoDeDesligamento;
import br.edu.puccampinas.consultafacil.protocol.Parceiro;
import br.edu.puccampinas.consultafacil.handler.ValidarCPFResponse;

import java.io.IOException;

public class TratadoraDeComunicadoDeDesligamento extends Thread
{
    private final Parceiro servidor;

    public TratadoraDeComunicadoDeDesligamento (Parceiro servidor) throws Exception {
        if(servidor == null) throw new Exception ("Porta invalida");

        this.servidor = servidor;
    }

    public void run() {
        try {
            for (;;) {
                Comunicado c = servidor.espie();
                if (c instanceof ComunicadoDeDesligamento)
                    throw new IOException("Servidor de CPF desligado");
                if (!(c instanceof ValidarCPFResponse))
                    throw new IOException("Resposta inesperada do servidor de CPF");
            }
        } catch (Exception erro) {
            servidor.indisponivel(erro);
        }
    }
}
