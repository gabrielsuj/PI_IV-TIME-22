package br.edu.puccampinas.consultafacil.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.*;
import org.bson.Document;
import org.bson.codecs.pojo.PojoCodecProvider;
import static org.bson.codecs.configuration.CodecRegistries.*;

public final class MongoConnection implements AutoCloseable {
    private final MongoClient client;
    private final MongoDatabase database;

    public MongoConnection(String uri, String databaseName) {
        var codecs = fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            fromProviders(PojoCodecProvider.builder().automatic(true).build()));
        var settings = MongoClientSettings.builder()
            .applyConnectionString(new ConnectionString(uri))
            .codecRegistry(codecs)
            .build();
        this.client = MongoClients.create(settings);
        this.database = client.getDatabase(databaseName);
    }

    public MongoDatabase database() { return database; }

    public void ping() { database.runCommand(new Document("ping", 1)); }

    @Override public void close() { client.close(); }
}