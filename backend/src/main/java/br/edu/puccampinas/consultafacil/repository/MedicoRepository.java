package br.edu.puccampinas.consultafacil.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.types.ObjectId;

/** Versão mínima, usada por agenda e bloqueios. O CRUD de médicos (#13) deve estender esta classe. */
public class MedicoRepository {
    private final MongoCollection<Document> col;

    public MedicoRepository(MongoDatabase db) { this.col = db.getCollection("medicos"); }

    public boolean existeAtivo(ObjectId id) {
        return col.countDocuments(Filters.and(Filters.eq("_id", id), Filters.eq("ativo", true))) > 0;
    }
}
