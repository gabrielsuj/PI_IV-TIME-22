package br.edu.puccampinas.consultafacil.api.handler;

import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.dto.AgendamentoRequest;
import br.edu.puccampinas.consultafacil.security.Autorizacao;
import br.edu.puccampinas.consultafacil.service.AgendamentoService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/** POST /consultas: confirma um agendamento (#21). 201 com a consulta criada; 409 se o horário não está mais livre. */
public class ConsultaHandler implements HttpHandler {
    private final AgendamentoService service;

    public ConsultaHandler(AgendamentoService service) { this.service = service; }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        // Perfil verificado em Rotas (#10); o serviço precisa saber quem é para o paciente agendar para si mesmo.
        var ator = Autorizacao.usuario(ex);
        Http.sendJson(ex, 201, service.agendar(Http.readJson(ex, AgendamentoRequest.class), ator));
    }
}
