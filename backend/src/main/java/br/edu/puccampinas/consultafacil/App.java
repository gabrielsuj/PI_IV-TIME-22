package br.edu.puccampinas.consultafacil;

import br.edu.puccampinas.consultafacil.config.*;
public class App {
    public static void main(String[] args) {
        AppConfig config = AppConfig.load();
        MongoConnection mongo = new MongoConnection(config.mongoUri(), config.mongoDatabase());
        mongo.ping();
        System.out.println("conectado ao Mongo: " + config.mongoDatabase());
        Runtime.getRuntime().addShutdownHook(new Thread(mongo::close));
        //depois subir hhtpserver na pora config.serverPort()
    }
}