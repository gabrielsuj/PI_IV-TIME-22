package br.edu.puccampinas.consultafacil;

import br.edu.puccampinas.consultafacil.config.*;
import br.edu.puccampinas.consultafacil.api.handler.PacienteHandler;
import br.edu.puccampinas.consultafacil.repository.PacienteRepository;
import br.edu.puccampinas.consultafacil.service.PacienteService;
import br.edu.puccampinas.consultafacil.service.PacienteValidator;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.io.IOException;

public class App {
    public static void main(String[] args) throws IOException {
        AppConfig config = AppConfig.load();
        MongoConnection mongo = new MongoConnection(config.mongoUri(), config.mongoDatabase());
        HttpServer server = null;
        try {
            mongo.ping();
            var repository = new PacienteRepository.Mongo(mongo.database());
            var service = new PacienteService(repository, new PacienteValidator());
            server = HttpServer.create(new InetSocketAddress(config.serverPort()), 0);
            server.createContext("/pacientes", new PacienteHandler(service));
            HttpServer servidor = server;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                servidor.stop(1);
                mongo.close();
            }));
            server.start();
            System.out.println("API de pacientes na porta " + config.serverPort());
        } catch (IOException | RuntimeException e) {
            if (server != null) server.stop(0);
            mongo.close();
            throw e;
        }
    }
}
