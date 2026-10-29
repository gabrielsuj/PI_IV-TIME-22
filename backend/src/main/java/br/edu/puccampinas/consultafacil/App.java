package br.edu.puccampinas.consultafacil;

import br.edu.puccampinas.consultafacil.api.Router;
import br.edu.puccampinas.consultafacil.api.handler.HealthHandler;
import br.edu.puccampinas.consultafacil.api.middleware.Cors;
import br.edu.puccampinas.consultafacil.config.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;

public class App {
    public static void main(String[] args) throws IOException {
        AppConfig config = AppConfig.load();
        MongoConnection mongo = new MongoConnection(config.mongoUri(), config.mongoDatabase());
        mongo.ping();
        System.out.println("conectado ao Mongo: " + config.mongoDatabase());

        Router router = new Router(new Cors(config.corsAllowedOrigins()))
            .add("GET", "/health", new HealthHandler());

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
