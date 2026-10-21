package br.edu.puccampinas.consultafacil.integration;

import br.edu.puccampinas.consultafacil.config.MongoConnection;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class MongoConnectionIntegrationTest {
    @Test
    void deveConectarNoBancoDeTeste() {
        try (var mongo = new MongoConnection("mongodb://localhost:27017", "consultafacil_test")) {
            assertDoesNotThrow(mongo::ping);
        }
    }
}