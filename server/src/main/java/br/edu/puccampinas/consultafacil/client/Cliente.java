package br.edu.puccampinas.consultafacil.client;

import br.edu.puccampinas.consultafacil.connection.TratadoraDeComunicadoDeDesligamento;
import br.edu.puccampinas.consultafacil.core.Teclado;
import br.edu.puccampinas.consultafacil.handler.ValidarCPFRequest;
import br.edu.puccampinas.consultafacil.handler.ValidarCPFResponse;
import br.edu.puccampinas.consultafacil.protocol.Comunicado;
import br.edu.puccampinas.consultafacil.protocol.Parceiro;
import br.edu.puccampinas.consultafacil.protocol.PedidoParaSair;

import java.net.*;
import java.io.*;

public class Cliente {
	public static final String HOST_PADRAO  = "localhost";
	public static final int    PORTA_PADRAO = 3000;

	public static void main (String[] args) {
        if (args.length>2) {
            System.err.println ("Uso esperado: java Cliente [HOST [PORTA]]\n");
            return;
        }

        Socket conexao=null;
        try {
            String host = Cliente.HOST_PADRAO;
            int    porta= Cliente.PORTA_PADRAO;

            if (args.length>0) host = args[0];
            if (args.length==2) porta = Integer.parseInt(args[1]);

            conexao = new Socket (host, porta);
        } catch(Exception erro) {
            System.err.println ("Indique o servidor e a porta corretos!\n");
            return;
        }

        ObjectOutputStream transmissor=null;
        try {
            transmissor = new ObjectOutputStream(conexao.getOutputStream());
        } catch(Exception erro) {
            System.err.println ("Indique o servidor e a porta corretos!\n");
            return;
        }

        ObjectInputStream receptor=null;
        try {
            receptor = new ObjectInputStream(conexao.getInputStream());
        } catch(Exception erro) {
            System.err.println ("Indique o servidor e a porta corretos!\n");
            return;
        }

        Parceiro servidor=null;
        try {
            servidor = new Parceiro (conexao, receptor, transmissor);
        } catch(Exception erro) {
            System.err.println ("Indique o servidor e a porta corretos!\n");
            return;
        }

        TratadoraDeComunicadoDeDesligamento tratadoraDeComunicadoDeDesligamento = null;
        try {
			tratadoraDeComunicadoDeDesligamento = new TratadoraDeComunicadoDeDesligamento (servidor);
		} catch(Exception ignored) {}

        tratadoraDeComunicadoDeDesligamento.start();

        Object ret;
        do {
            // Fazer que ret receba do Backend
            // ret = servidor.envie();
            try {
                if (ret instanceof ValidarCPFRequest) {
                    ValidarCPFRequest cpf = null;
                    try {
                        cpf = (ValidarCPFRequest)(ret);
                        servidor.receba(new ValidarCPFRequest(cpf.getCPF()));
                    } catch (Exception e) {
                        System.err.println("Não foi recebido o CPF corretamente.");
                    }

                    Comunicado comunicado;
                    do {
                        comunicado = (Comunicado)(servidor.espie());
                    } while (!(comunicado instanceof ValidarCPFResponse));

                    // Mandar resposta para o backend
                    // ValidarCPFResponse resposta = (ValidarCPFResponse)(servidor.envie());
                }
            } catch (Exception ignored) {}
        } while(!(ret instanceof PedidoParaSair));

		try {
			servidor.receba(new PedidoParaSair());
		} catch(Exception ignored) {}
		
		System.out.println ("Obrigado por usar este programa!");
		System.exit(0);
	}
}
