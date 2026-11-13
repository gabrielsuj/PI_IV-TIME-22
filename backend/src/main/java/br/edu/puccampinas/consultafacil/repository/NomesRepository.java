package br.edu.puccampinas.consultafacil.repository;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** Busca nomes de exibição em lote (uma consulta por coleção), para montar listagens sem N+1. */
public interface NomesRepository {
    Map<ObjectId, String> pacientes(Collection<ObjectId> ids);
    Map<ObjectId, String> especialidades(Collection<ObjectId> ids);
    Map<ObjectId, String> medicos(Collection<ObjectId> ids);

    /** Usado pela listagem e pelo detalhe de consultas (#30). Implementações antigas devolvem vazio. */
    default Map<ObjectId, String> medicos(Collection<ObjectId> ids) { return Map.of(); }

    final class Mongo implements NomesRepository {
        private final MongoDatabase db;

        public Mongo(MongoDatabase db) { this.db = db; }

        @Override public Map<ObjectId, String> pacientes(Collection<ObjectId> ids) { return nomes("pacientes", ids); }
        @Override public Map<ObjectId, String> especialidades(Collection<ObjectId> ids) { return nomes("especialidades", ids); }
        @Override public Map<ObjectId, String> medicos(Collection<ObjectId> ids) { return nomes("medicos", ids); }

        private Map<ObjectId, String> nomes(String colecao, Collection<ObjectId> ids) {
            Map<ObjectId, String> mapa = new HashMap<>();
            if (ids.isEmpty()) return mapa;
            db.getCollection(colecao).find(Filters.in("_id", ids)).projection(Projections.include("nome"))
                    .forEach((Document d) -> mapa.put(d.getObjectId("_id"), d.getString("nome")));
            return mapa;
        }
    }
}
