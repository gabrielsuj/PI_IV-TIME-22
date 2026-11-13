package br.edu.puccampinas.consultafacil.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.Optional;

import static com.mongodb.client.model.Filters.eq;

/**
 * Descobre o cadastro de médico de quem fez login com perfil MEDICO (#27).
 * O vínculo é `medicos.usuarioId` → `usuarios._id` (database/init/07-esquema-medicos.js), igual ao
 * {@link PacienteDoUsuarioRepository}. Médico sem usuarioId não tem login.
 */
public class MedicoDoUsuarioRepository {
    private final MongoCollection<Document> medicos;

    public MedicoDoUsuarioRepository(MongoDatabase db) {
        this.medicos = db.getCollection("medicos");
    }

    /** _id do médico ligado ao usuário, ou vazio se o login não tiver cadastro de médico. */
    public Optional<ObjectId> buscar(String usuarioId) {
        if (usuarioId == null || !ObjectId.isValid(usuarioId)) return Optional.empty();

        Document medico = medicos.find(eq("usuarioId", new ObjectId(usuarioId))).first();
        if (medico == null) return Optional.empty();
        return Optional.of(medico.getObjectId("_id"));
    }
}
