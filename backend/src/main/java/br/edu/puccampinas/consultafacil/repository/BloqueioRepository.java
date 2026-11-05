package br.edu.puccampinas.consultafacil.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class BloqueioRepository {
    private final MongoCollection<Document> col;

    public BloqueioRepository(MongoDatabase db) { this.col = db.getCollection("bloqueios"); }

    /**
     * Bloqueios que se sobrepõem à janela [inicio, fim).
     * Também deve ser usado pelo cálculo de disponibilidade (#20).
     */
    public List<Document> sobrepostos(ObjectId medicoId, Date inicio, Date fim) {
        return col.find(Filters.and(
                        Filters.eq("medicoId", medicoId),
                        Filters.lt("inicio", fim),
                        Filters.gt("fim", inicio)))
                .sort(Sorts.ascending("inicio"))
                .into(new ArrayList<>());
    }

    public List<Document> doMedico(ObjectId medicoId) {
        return col.find(Filters.eq("medicoId", medicoId))
                .sort(Sorts.ascending("inicio"))
                .into(new ArrayList<>());
    }

    public Document inserir(Document d) {
        return MongoErros.traduzir(() -> {
            col.insertOne(d);   // o driver preenche o _id no próprio documento
            return d;
        });
    }

    public boolean remover(ObjectId id) {
        return col.deleteOne(Filters.eq("_id", id)).getDeletedCount() > 0;
    }
}
