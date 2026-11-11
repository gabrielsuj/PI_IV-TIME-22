package br.edu.puccampinas.consultafacil.repository;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import static com.mongodb.client.model.Filters.eq;

/** Apenas inserção. Repetir o mesmo evento é seguro; divergências não são ocultadas. */
@FunctionalInterface
public interface HistoricoRepository {
    void registrar(Document evento);

    /** Base para documentos legados; destinos sem histórico anterior retornam zero. */
    default int ultimaSequencia(org.bson.types.ObjectId consultaId) { return 0; }

    class Mongo implements HistoricoRepository {
        private final MongoCollection<Document> col;
        public Mongo(MongoDatabase db) { col = db.getCollection("historico"); }

        @Override public int ultimaSequencia(org.bson.types.ObjectId consultaId) {
            Document ultimo = col.find(eq("consultaId", consultaId))
                    .sort(com.mongodb.client.model.Sorts.descending("sequencia")).first();
            return ultimo == null ? 0 : ultimo.getInteger("sequencia");
        }

        @Override public void registrar(Document evento) {
            try {
                col.insertOne(evento);
            } catch (MongoWriteException e) {
                if (e.getError().getCode() != 11000) throw e;
                Document existente = col.find(eq("_id", evento.getObjectId("_id"))).first();
                if (!evento.equals(existente))
                    throw new IllegalStateException("Conflito de identidade ou sequência do histórico", e);
                // A inserção anterior foi confirmada. É seguro reconhecer a pendência.
            }
        }
    }
}
