package br.edu.puccampinas.consultafacil.repository;

import br.edu.puccampinas.consultafacil.domain.model.Paciente;
import br.edu.puccampinas.consultafacil.domain.exception.ValidacaoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Indexes.ascending;

public interface PacienteRepository {
    Paciente cadastrar(Paciente paciente);
    Optional<Paciente> atualizar(Paciente paciente);
    Optional<Paciente> consultar(String id);
    List<Paciente> listar(String busca);
    boolean cpfEmUso(String cpf, String excetoId);
    boolean emailEmUso(String email, String excetoId);

    // A interface permite testar as regras sem depender de um banco real.
    class Mongo implements PacienteRepository {
        private final MongoCollection<Document> collection;

        public Mongo(MongoDatabase database) {
            collection = database.getCollection("pacientes");
            // Protege também bancos já inicializados contra gravações concorrentes.
            collection.createIndex(ascending("cpf"), new IndexOptions().unique(true).name("uk_paciente_cpf"));
            collection.createIndex(ascending("email"), new IndexOptions().unique(true).name("uk_paciente_email"));
        }

        public Paciente cadastrar(Paciente paciente) {
            try { collection.insertOne(documento(paciente)); }
            catch (MongoWriteException e) { throw traduzir(e); }
            return paciente;
        }

        public Optional<Paciente> atualizar(Paciente paciente) {
            try {
                var result = collection.updateOne(eq("_id", new ObjectId(paciente.id())),
                        new Document("$set", new Document("nome", paciente.nome())
                                .append("cpf", paciente.cpf()).append("email", paciente.email())));
                return result.getMatchedCount() == 0 ? Optional.empty() : Optional.of(paciente);
            } catch (MongoWriteException e) { throw traduzir(e); }
        }

        public Optional<Paciente> consultar(String id) {
            return Optional.ofNullable(collection.find(eq("_id", new ObjectId(id))).first()).map(this::paciente);
        }

        public List<Paciente> listar(String busca) {
            Bson filtro = new Document();
            if (!busca.isBlank()) {
                String literal = Pattern.quote(busca);
                String cpfBusca = busca.matches("[0-9.\\-]+") ? busca.replace(".", "").replace("-", "") : busca;
                filtro = or(regex("nome", literal, "i"), regex("email", literal, "i"),
                        regex("cpf", Pattern.quote(cpfBusca)));
            }
            return collection.find(filtro).sort(ascending("nome", "_id")).map(this::paciente).into(new ArrayList<>());
        }

        public boolean cpfEmUso(String cpf, String excetoId) { return emUso("cpf", cpf, excetoId); }
        public boolean emailEmUso(String email, String excetoId) { return emUso("email", email, excetoId); }

        private boolean emUso(String campo, String valor, String excetoId) {
            Bson filtro = eq(campo, valor);
            if (excetoId != null) filtro = and(filtro, ne("_id", new ObjectId(excetoId)));
            return collection.find(filtro).first() != null;
        }

        private RuntimeException traduzir(MongoWriteException e) {
            if (e.getError().getCode() == 11000)
                return new ValidacaoException("CPF ou e-mail já cadastrado para outro paciente.");
            if (e.getError().getCode() == 121)
                return new ValidacaoException("Dados do paciente não atendem aos campos obrigatórios e formatos exigidos.");
            return e;
        }

        private Document documento(Paciente p) {
            return new Document("_id", new ObjectId(p.id())).append("nome", p.nome())
                    .append("cpf", p.cpf()).append("email", p.email());
        }

        private Paciente paciente(Document d) {
            return new Paciente(d.getObjectId("_id").toHexString(), d.getString("nome"),
                    d.getString("cpf"), d.getString("email"));
        }
    }
}
