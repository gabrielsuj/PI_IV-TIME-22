package br.edu.puccampinas.consultafacil;

import br.edu.puccampinas.consultafacil.handler.ValidarCPFRequest;
import br.edu.puccampinas.consultafacil.handler.ValidarCPFResponse;
import br.edu.puccampinas.consultafacil.protocol.ComunicadoDeDesligamento;
import org.junit.Test;

import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class ServidorTest {
    @Test(timeout = 15000) public void entradaFechadaEncerraEAvisaClientes() throws Exception { encerrar(null); }
    @Test(timeout = 15000) public void comandoDesativarEncerraEAvisaClientes() throws Exception { encerrar("desativar\n"); }

    private void encerrar(String comando) throws Exception {
        int porta;
        try (ServerSocket reserva = new ServerSocket(0)) { porta = reserva.getLocalPort(); }
        Process processo = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Servidor.class.getName(), Integer.toString(porta))
                .redirectErrorStream(true).start();
        try (BufferedReader console = processo.inputReader(StandardCharsets.UTF_8)) {
            String linha;
            do { linha = console.readLine(); assertNotNull("Servidor não iniciou", linha); }
            while (!linha.contains("O servidor esta ativo!"));
            try (Socket conexao = new Socket(InetAddress.getLoopbackAddress(), porta)) {
                conexao.setSoTimeout(5000);
                ObjectOutputStream saida = new ObjectOutputStream(conexao.getOutputStream());
                ObjectInputStream entrada = new ObjectInputStream(conexao.getInputStream());
                saida.writeObject(new ValidarCPFRequest("52998224725"));
                saida.flush();
                assertTrue(((ValidarCPFResponse) entrada.readObject()).isValido());
                if (comando != null) processo.getOutputStream().write(comando.getBytes(StandardCharsets.UTF_8));
                processo.getOutputStream().close();
                assertTrue(entrada.readObject() instanceof ComunicadoDeDesligamento);
            }
            assertTrue("Processo não encerrou", processo.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, processo.exitValue());
            String restante = console.lines().reduce("", (a, b) -> a + b + "\n");
            assertTrue(restante, restante.contains("O servidor foi desativado!"));
            assertFalse(restante, restante.contains("NullPointerException"));
            try (ServerSocket liberada = new ServerSocket(porta)) { assertEquals(porta, liberada.getLocalPort()); }
        } finally {
            processo.destroyForcibly();
            processo.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
