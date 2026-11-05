package br.edu.puccampinas.consultafacil.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.Optional;

public class AgendaRepository {
    private final MongoCollection<Document> col;

    public AgendaRepository(MongoDatabase db) { this.col = db.getCollection("agendas"); }

    public Optional<Document> buscarPorMedico(ObjectId medicoId) {
        return Optional.ofNullable(col.find(Filters.eq("medicoId", medicoId)).first());
    }

    /** Cria ou substitui a configuração semanal do médico (uma agenda por médico). */
    public Document salvar(ObjectId medicoId, Document dados) {
        return MongoErros.traduzir(() -> col.findOneAndUpdate(
                Filters.eq("medicoId", medicoId),
                new Document("$set", dados),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)));
    }
}
