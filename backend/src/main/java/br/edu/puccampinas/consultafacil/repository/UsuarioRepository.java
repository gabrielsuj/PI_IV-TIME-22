package br.edu.puccampinas.consultafacil.repository;

import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.domain.model.Usuario;
import br.edu.puccampinas.consultafacil.security.SenhaHash;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.set;

public interface UsuarioRepository {
    /** Busca pelo e-mail já normalizado (minúsculas, sem espaços). */
    Optional<Usuario> buscarPorEmail(String email);

    void registrarAcesso(String id, Instant quando);

    // A interface permite testar o login sem depender de um banco real.
    class Mongo implements UsuarioRepository {
        private final MongoCollection<Document> col;

        public Mongo(MongoDatabase db) { this.col = db.getCollection("usuarios"); }

        public Optional<Usuario> buscarPorEmail(String email) {
            return Optional.ofNullable(col.find(eq("email", email)).first()).map(Mongo::usuario);
        }

        public void registrarAcesso(String id, Instant quando) {
            col.updateOne(eq("_id", new ObjectId(id)), set("ultimoAcessoEm", Date.from(quando)));
        }

        private static Usuario usuario(Document d) {
            Document s = d.get("senha", Document.class);
            var senha = s == null ? null : new SenhaHash(
                    s.getString("hash"), s.getString("salt"), s.getString("algoritmo"), s.getInteger("iteracoes"));
            return new Usuario(d.getObjectId("_id").toHexString(), d.getString("nome"), d.getString("email"),
                    Perfil.valueOf(d.getString("perfil")), Boolean.TRUE.equals(d.getBoolean("ativo")), senha);
        }
    }
}
