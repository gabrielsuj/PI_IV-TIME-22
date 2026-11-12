package br.edu.puccampinas.consultafacil.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.Optional;

import static com.mongodb.client.model.Filters.eq;

/**
 * Descobre o cadastro de paciente de quem fez login com perfil PACIENTE (#21).
 * O vínculo é `pacientes.usuarioId` → `usuarios._id`, conforme docs/modelagem/usuarios-pacientes.md.
 * Paciente cadastrado só pela secretaria não tem usuarioId (não agenda sozinho).
 */
public class PacienteDoUsuarioRepository {
    private final MongoCollection<Document> pacientes;

    public PacienteDoUsuarioRepository(MongoDatabase db) {
        this.pacientes = db.getCollection("pacientes");
    }

    /** _id do paciente ligado ao usuário, ou vazio se o login não tiver cadastro de paciente. */
    public Optional<ObjectId> buscar(String usuarioId) {
        if (usuarioId == null || !ObjectId.isValid(usuarioId)) return Optional.empty();

        Document paciente = pacientes.find(eq("usuarioId", new ObjectId(usuarioId))).first();
        if (paciente == null) return Optional.empty();
        return Optional.of(paciente.getObjectId("_id"));
    }
}
