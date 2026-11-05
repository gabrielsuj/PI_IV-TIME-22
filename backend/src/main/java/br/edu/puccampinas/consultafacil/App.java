package br.edu.puccampinas.consultafacil;

import br.edu.puccampinas.consultafacil.api.Router;
import br.edu.puccampinas.consultafacil.api.handler.AgendaHandler;
import br.edu.puccampinas.consultafacil.api.handler.BloqueioHandler;
import br.edu.puccampinas.consultafacil.api.handler.HealthHandler;
import br.edu.puccampinas.consultafacil.api.middleware.Cors;
import br.edu.puccampinas.consultafacil.config.*;
import br.edu.puccampinas.consultafacil.repository.AgendaRepository;
import br.edu.puccampinas.consultafacil.repository.BloqueioRepository;
import br.edu.puccampinas.consultafacil.repository.MedicoRepository;
import br.edu.puccampinas.consultafacil.service.AgendaService;
import br.edu.puccampinas.consultafacil.service.BloqueioService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;

public class App {
    public static void main(String[] args) throws IOException {
        AppConfig config = AppConfig.load();
        MongoConnection mongo = new MongoConnection(config.mongoUri(), config.mongoDatabase());
        mongo.ping();
        System.out.println("conectado ao Mongo: " + config.mongoDatabase());

        var db = mongo.database();
        var medicos = new MedicoRepository(db);
        var agendaRepo = new AgendaRepository(db);
        var agendaHandler = new AgendaHandler(new AgendaService(agendaRepo, medicos));
        var bloqueioHandler = new BloqueioHandler(
            new BloqueioService(new BloqueioRepository(db), agendaRepo, medicos));

        Router router = new Router(new Cors(config.corsAllowedOrigins()))
            .add("GET", "/health", new HealthHandler())
            .add("GET", "/agendas", agendaHandler)
            .add("PUT", "/agendas", agendaHandler)
            .add("GET", "/bloqueios", bloqueioHandler)
            .add("POST", "/bloqueios", bloqueioHandler)
            .add("DELETE", "/bloqueios", bloqueioHandler);

        HttpServer server = HttpServer.create(new InetSocketAddress(config.serverPort()), 0);
        server.createContext("/", router);
        server.start();
        System.out.println("servidor rodando na porta " + config.serverPort());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            mongo.close();
        }));
    }
}
