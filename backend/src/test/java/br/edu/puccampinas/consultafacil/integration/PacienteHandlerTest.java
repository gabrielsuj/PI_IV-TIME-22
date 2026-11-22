package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.api.handler.PacienteHandler;
import br.edu.puccampinas.consultafacil.service.*;
import br.edu.puccampinas.consultafacil.unit.PacienteServiceTest.EmMemoria;
import br.edu.puccampinas.consultafacil.domain.exception.ServidorCpfIndisponivelException;
import br.edu.puccampinas.consultafacil.handler.ValidarCPFRequest;
import br.edu.puccampinas.consultafacil.protocol.ComunicadoDeDesligamento;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.net.http.*;
import java.io.EOFException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Murilo Rigoni (criador)
 */

class PacienteHandlerTest {
    private HttpServer server;
    private HttpClient client;
    private String base;
    private final ObjectMapper json = new ObjectMapper();
    private static final String ANA = """
            {"nome":"Ana Silva","cpf":"529.982.247-25","email":"ANA@EXEMPLO.COM"}
            """;

    
    @BeforeEach
    void iniciar() throws Exception {
        iniciarComValidador(cpf -> {});
    }

    private void iniciarComValidador(ValidadorCpf validadorCpf)
            throws Exception {

        if (server != null) {
            server.stop(0);
        }

        server = HttpServer.create(
            new InetSocketAddress("127.0.0.1", 0), 0
        );

        server.createContext("/pacientes", new PacienteHandler(
            new PacienteService(
                new EmMemoria(),
                new PacienteValidator(),
                validadorCpf
            )
        ));

        server.start();

        base = "http://127.0.0.1:"
            + server.getAddress().getPort();

        client = HttpClient.newHttpClient();
    }

       

    @AfterEach void encerrar() {
        if (client != null) client.close();
        if (server != null) server.stop(0);
    }

    @Test void cadastraConsultaListaBuscaEEdita() throws Exception {
        var criado = enviar("POST", "/pacientes", ANA);
        assertEquals(201, criado.statusCode());
        assertTrue(criado.headers().firstValue("Content-Type").orElseThrow().contains("application/json"));
        String rota = criado.headers().firstValue("Location").orElseThrow();
        var paciente = json.readTree(criado.body());
        assertEquals("52998224725", paciente.get("cpf").asText());
        assertEquals("ana@exemplo.com", paciente.get("email").asText());
        assertEquals(paciente, json.readTree(enviar("GET", rota, "").body()));
        assertEquals(1, json.readTree(enviar("GET", "/pacientes", "").body()).size());
        for (String busca : new String[]{"ana", "529.982.247-25", "ana%40exemplo.com"}) {
            var resposta = enviar("GET", "/pacientes?busca=" + busca, "");
            assertEquals(200, resposta.statusCode());
            assertEquals(1, json.readTree(resposta.body()).size());
        }
        assertEquals(0, json.readTree(enviar("GET", "/pacientes?busca=ausente", "").body()).size());
        var editado = enviar("PUT", rota, ANA.replace("Ana Silva", "Ana Souza"));
        assertEquals(200, editado.statusCode());
        assertEquals("Ana Souza", json.readTree(enviar("GET", rota, "").body()).get("nome").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{", "{}",
            "{\"nome\":5,\"cpf\":\"52998224725\",\"email\":\"ana@exemplo.com\"}",
            "{\"nome\":\"Ana\",\"cpf\":\"00000000000\",\"email\":\"ana@exemplo.com\"}",
            "{\"nome\":\"Ana\",\"cpf\":\"52998224725\",\"email\":\"invalido\"}",
            "{\"nome\":\"Ana\",\"cpf\":\"52998224725\"}",
            "{\"nome\":\"Ana\",\"email\":\"ana@exemplo.com\"}",
            "{\"cpf\":\"52998224725\",\"email\":\"ana@exemplo.com\"}",
            "{} {}"})
    void dadosInvalidosRetornam400ComMensagem(String body) throws Exception {
        validarErro(enviar("POST", "/pacientes", body), 400);
    }

    @Test void duplicidadeRetorna400() throws Exception {
        enviar("POST", "/pacientes", ANA);
        validarErro(enviar("POST", "/pacientes", ANA.replace("ANA@EXEMPLO.COM", "outro@exemplo.com")), 400);
        validarErro(enviar("POST", "/pacientes", ANA.replace("529.982.247-25", "11144477735")), 400);
    }

    @Test void edicaoInvalidaEDuplicadaRetorna400() throws Exception {
        String rota = enviar("POST", "/pacientes", ANA).headers().firstValue("Location").orElseThrow();
        validarErro(enviar("PUT", rota, "{}"), 400);
        var outro = enviar("POST", "/pacientes", ANA.replace("529.982.247-25", "11144477735")
                .replace("ANA@EXEMPLO.COM", "outro@exemplo.com"));
        String rotaOutro = outro.headers().firstValue("Location").orElseThrow();
        validarErro(enviar("PUT", rotaOutro, ANA), 400);
    }

    @Test void distingueIdInvalidoAusenteRotasEMetodos() throws Exception {
        validarErro(enviar("GET", "/pacientes/xyz", ""), 400);
        validarErro(enviar("GET", "/pacientes/000000000000000000000000", ""), 404);
        validarErro(enviar("PUT", "/pacientes/000000000000000000000000", ANA), 404);
        validarErro(enviar("GET", "/pacientesXYZ", ""), 404);
        validarErro(enviar("GET", "/pacientes/a/b", ""), 404);
        var resposta = enviar("DELETE", "/pacientes", "");
        validarErro(resposta, 405);
        assertEquals("GET, POST", resposta.headers().firstValue("Allow").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Timeout(10)
    void desligamentoTcpRetorna503EMantemHttpAtivo(boolean comAviso) throws Exception {
        try (ServerSocket tcp = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            tcp.setSoTimeout(3000);
            var executor = Executors.newSingleThreadExecutor(tarefa -> {
                Thread thread = new Thread(tarefa);
                thread.setDaemon(true);
                return thread;
            });
            try {
                var atendimento = executor.submit(() -> {
                    try (Socket conexao = tcp.accept()) {
                        conexao.setSoTimeout(3000);
                        try (ObjectOutputStream saida = new ObjectOutputStream(conexao.getOutputStream());
                             ObjectInputStream entrada = new ObjectInputStream(conexao.getInputStream())) {
                            assertInstanceOf(ValidarCPFRequest.class, entrada.readObject());
                            if (comAviso) {
                                saida.writeObject(new ComunicadoDeDesligamento());
                                saida.flush();
                                assertThrows(EOFException.class, entrada::readObject);
                            }
                        }
                    }
                    return null;
                });
                iniciarComValidador(new ValidadorCpfTcp(tcp.getInetAddress().getHostAddress(), tcp.getLocalPort()));
                var resposta = enviar("POST", "/pacientes", ANA);
                assertEquals(503, resposta.statusCode());
                assertEquals("Serviço de validação de CPF temporariamente indisponível.",
                        json.readTree(resposta.body()).get("mensagem").asText());
                var consulta = enviar("GET", "/pacientes", "");
                assertEquals(200, consulta.statusCode());
                assertEquals(0, json.readTree(consulta.body()).size());
                atendimento.get(3, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void servidorCpfIndisponivelRetorna503() throws Exception {

        ValidadorCpf indisponivel = cpf -> {
            throw new ServidorCpfIndisponivelException(
                new ConnectException("Connection refused")
            );
        };

        iniciarComValidador(indisponivel);

        var resposta = enviar("POST", "/pacientes", ANA);

        assertEquals(503, resposta.statusCode());

        var corpo = json.readTree(resposta.body());

        assertEquals(
            "Serviço de validação de CPF temporariamente indisponível.",
            corpo.get("mensagem").asText()
        );

        assertFalse(resposta.body().contains("Connection refused"));

        var consulta = enviar("GET", "/pacientes", "");

        assertEquals(200, consulta.statusCode());
        assertEquals(0, json.readTree(consulta.body()).size());
    }


    private HttpResponse<String> enviar(String metodo, String rota, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + rota))
                .header("Content-Type", "application/json")
                .method(metodo, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void validarErro(HttpResponse<String> resposta, int status) throws Exception {
        assertEquals(status, resposta.statusCode(), resposta.body());
        assertFalse(json.readTree(resposta.body()).get("mensagem").asText().isBlank());
    }
}
