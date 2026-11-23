package br.edu.puccampinas.consultafacil.connection;

import br.edu.puccampinas.consultafacil.handler.ValidarCPFRequest;
import br.edu.puccampinas.consultafacil.handler.ValidarCPFResponse;
import br.edu.puccampinas.consultafacil.protocol.Parceiro;
import br.edu.puccampinas.consultafacil.protocol.PedidoParaSair;
import org.junit.Test;

import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class AceitadoraDeConexaoTest {
    @Test public void listaNulaNaoOcupaAPorta() throws Exception {
        int porta;
        try (ServerSocket reserva = new ServerSocket(0)) { porta = reserva.getLocalPort(); }
        Exception erro = assertThrows(Exception.class, () -> new AceitadoraDeConexao(Integer.toString(porta), null));
        assertEquals("Usuarios ausentes", erro.getMessage());
        try (ServerSocket livre = new ServerSocket(porta)) { assertEquals(porta, livre.getLocalPort()); }
    }

    @Test(timeout = 15000) public void falhaConstruindoSupervisoraNaoImpedeProximoCliente() throws Exception {
        Process processo = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Cenario.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue("Cenário não encerrou", processo.waitFor(10, TimeUnit.SECONDS));
            String saida = new String(processo.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(saida, 0, processo.exitValue());
        } finally {
            processo.destroyForcibly();
            processo.waitFor(5, TimeUnit.SECONDS);
        }
    }

    public static class Cenario {
        public static void main(String[] args) throws Exception {
            ArrayList<Parceiro> usuarios = new ArrayList<>();
            AceitadoraDeConexao aceitadora = new AceitadoraDeConexao("0", usuarios);
            Field lista = AceitadoraDeConexao.class.getDeclaredField("usuarios");
            lista.setAccessible(true);
            lista.set(aceitadora, null);
            Field pedido = AceitadoraDeConexao.class.getDeclaredField("pedido");
            pedido.setAccessible(true);
            int porta = ((ServerSocket) pedido.get(aceitadora)).getLocalPort();
            aceitadora.setDaemon(true);
            aceitadora.start();
            try (Socket recusada = new Socket(InetAddress.getLoopbackAddress(), porta)) {
                recusada.setSoTimeout(3000);
                assertEquals(-1, recusada.getInputStream().read());
            }
            assertTrue(aceitadora.isAlive());
            lista.set(aceitadora, usuarios);
            try (Socket conexao = new Socket(InetAddress.getLoopbackAddress(), porta)) {
                conexao.setSoTimeout(3000);
                ObjectOutputStream saida = new ObjectOutputStream(conexao.getOutputStream());
                ObjectInputStream entrada = new ObjectInputStream(conexao.getInputStream());
                saida.writeObject(new ValidarCPFRequest("52998224725"));
                saida.flush();
                assertTrue(((ValidarCPFResponse) entrada.readObject()).isValido());
                saida.writeObject(new PedidoParaSair());
                saida.flush();
                assertEquals(-1, entrada.read());
            }
        }
    }
}
