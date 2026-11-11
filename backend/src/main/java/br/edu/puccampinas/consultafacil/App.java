
package br.edu.puccampinas.consultafacil;

import br.edu.puccampinas.consultafacil.api.Router;
import br.edu.puccampinas.consultafacil.api.handler.AgendaHandler;
import br.edu.puccampinas.consultafacil.api.handler.AuthHandler;
import br.edu.puccampinas.consultafacil.api.handler.BloqueioHandler;
import br.edu.puccampinas.consultafacil.api.handler.HealthHandler;
import br.edu.puccampinas.consultafacil.api.handler.PacienteHandler;
import br.edu.puccampinas.consultafacil.api.middleware.Cors;

import br.edu.puccampinas.consultafacil.config.*;
import br.edu.puccampinas.consultafacil.repository.*;
import br.edu.puccampinas.consultafacil.security.*;
import br.edu.puccampinas.consultafacil.service.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;

public class App {
    public static void main(String[] args) throws IOException {

        AppConfig config = AppConfig.load();
        MongoConnection mongo = new MongoConnection(
            config.mongoUri(),
            config.mongoDatabase()
        );

        mongo.ping();
        System.out.println("Conectado ao Mongo: " + config.mongoDatabase());

        var db = mongo.database();

        // CRUD de pacientes (sua implementação)
        var pacienteRepo = new PacienteRepository.Mongo(db);
        var pacienteService = new PacienteService(
            pacienteRepo,
            new PacienteValidator()
        );
        var pacienteHandler = new PacienteHandler(pacienteService);

        // Agendas (develop)
        var medicos = new MedicoRepository(db);
        var agendaRepo = new AgendaRepository(db);
        var agendaHandler = new AgendaHandler(
            new AgendaService(agendaRepo, medicos)
        );

        // Bloqueios (develop)
        var bloqueioHandler = new BloqueioHandler(
            new BloqueioService(
                new BloqueioRepository(db),
                agendaRepo,
                medicos
            )
        );

        // Login e sessão (#9)
        var sessoes = new Sessoes(Duration.ofMinutes(config.sessionTtlMinutes()));
        var autenticador = new Autenticador(sessoes);
        var authHandler = new AuthHandler(
            new AuthService(new UsuarioRepository.Mongo(db), new SenhaHasher(), sessoes, Clock.systemUTC()),
            autenticador
        );

        // Rotas da aplicação
        var cors = new Cors(config.corsAllowedOrigins());
        Router router = new Router(cors)
            .add("GET", "/health", new HealthHandler())
            .add("POST", "/auth/login", authHandler)
            .add("POST", "/auth/logout", authHandler)
            .add("GET", "/auth/me", authHandler)
            .add("GET", "/agendas", agendaHandler)
            .add("PUT", "/agendas", agendaHandler)
            .add("GET", "/bloqueios", bloqueioHandler)
            .add("POST", "/bloqueios", bloqueioHandler)
            .add("DELETE", "/bloqueios", bloqueioHandler);
     


        HttpServer server = HttpServer.create(
            new InetSocketAddress(config.serverPort()), 0
        );

        server.createContext("/", router);

        // Inclui /pacientes/{id} para consulta e edição.
        server.createContext("/pacientes", exchange -> {
            try {
                cors.addHeaders(exchange);

                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }

                pacienteHandler.handle(exchange);
            } finally {
                exchange.close();
            }
        });

        server.start();

        System.out.println(
            "Servidor rodando na porta " + config.serverPort()
        );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            mongo.close();
        }));
    }
}
