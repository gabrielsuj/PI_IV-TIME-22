/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

package br.edu.puccampinas.consultafacil.repository;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;


/** Apenas inserção. Repetir o mesmo evento é seguro; divergências não são ocultadas. */
@FunctionalInterface
public interface HistoricoRepository {
    void registrar(Document evento);

    /** Base para documentos legados; destinos sem histórico anterior retornam zero. */
    default int ultimaSequencia(org.bson.types.ObjectId consultaId) { return 0; }

    class Mongo implements HistoricoRepository, HistoricoLeituraRepository {
        private final MongoCollection<Document> col;
        public Mongo(MongoDatabase db) { col = db.getCollection("historico"); }

        // A sequência crescente mantém a continuidade entre páginas, independentemente
        // da ordem em que os eventos foram inseridos na coleção.
        @Override public List<Document> listarPorConsulta(ObjectId consultaId, int aposSequencia, int limite) {
            if (consultaId == null) throw new IllegalArgumentException("Consulta é obrigatória para ler o histórico");
            if (aposSequencia < 0) throw new IllegalArgumentException("Sequência inicial não pode ser negativa");
            if (limite < 1 || limite > LIMITE_MAXIMO)
                throw new IllegalArgumentException("Limite do histórico deve ser de 1 a " + LIMITE_MAXIMO);
            return col.find(and(eq("consultaId", consultaId), gt("sequencia", aposSequencia)))
                    .sort(Sorts.ascending("sequencia"))
                    .limit(limite)
                    .into(new ArrayList<>());
        }

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
