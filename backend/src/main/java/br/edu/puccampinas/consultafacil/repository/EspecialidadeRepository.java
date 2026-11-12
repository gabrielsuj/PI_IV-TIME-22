package br.edu.puccampinas.consultafacil.repository;

import br.edu.puccampinas.consultafacil.api.dto.EspecialidadeResponse;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Indexes.ascending;

public interface EspecialidadeRepository {
    EspecialidadeResponse cadastrar(EspecialidadeResponse especialidade);
    Optional<EspecialidadeResponse> atualizar(EspecialidadeResponse especialidade);
    Optional<EspecialidadeResponse> consultar(ObjectId id);
    List<EspecialidadeResponse> listar();

    class Mongo implements EspecialidadeRepository {
        private final MongoCollection<Document> col;

        public Mongo(MongoDatabase db) { col = db.getCollection("especialidades"); }

        public EspecialidadeResponse cadastrar(EspecialidadeResponse e) {
            return MongoErros.traduzir(() -> {
                col.insertOne(documento(e).append("_id", new ObjectId(e.id())));
                return e;
            }, "Código de especialidade já cadastrado");
        }

        public Optional<EspecialidadeResponse> atualizar(EspecialidadeResponse e) {
            return MongoErros.traduzir(() -> {
                var resultado = col.updateOne(eq("_id", new ObjectId(e.id())), new Document("$set", documento(e)));
                return resultado.getMatchedCount() == 0 ? Optional.empty() : Optional.of(e);
            }, "Código de especialidade já cadastrado");
        }

        public Optional<EspecialidadeResponse> consultar(ObjectId id) {
            return Optional.ofNullable(col.find(eq("_id", id)).first()).map(this::resposta);
        }

        public List<EspecialidadeResponse> listar() {
            return col.find().sort(ascending("nome", "_id")).map(this::resposta).into(new ArrayList<>());
        }

        private Document documento(EspecialidadeResponse e) {
            return new Document("codigo", e.codigo()).append("nome", e.nome()).append("ativa", e.ativa());
        }

        private EspecialidadeResponse resposta(Document d) {
            return new EspecialidadeResponse(d.getObjectId("_id").toHexString(), d.getString("codigo"),
                    d.getString("nome"), d.getBoolean("ativa"));
        }
    }
}
