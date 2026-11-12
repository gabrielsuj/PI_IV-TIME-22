package br.edu.puccampinas.consultafacil.repository;

import br.edu.puccampinas.consultafacil.api.dto.MedicoResponse;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.types.ObjectId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static com.mongodb.client.model.Indexes.ascending;

/** Cadastro de médicos e consulta de atividade para agenda/bloqueios. */
public class MedicoRepository implements MedicoCadastroRepository {
    private final MongoCollection<Document> col;

    public MedicoRepository(MongoDatabase db) { this.col = db.getCollection("medicos"); }

    public boolean existeAtivo(ObjectId id) {
        return col.countDocuments(Filters.and(Filters.eq("_id", id), Filters.eq("ativo", true))) > 0;
    }

    public boolean existeAtivoComEspecialidade(ObjectId especialidadeId) {
        return col.find(Filters.and(Filters.eq("especialidadeIds", especialidadeId),
                Filters.eq("ativo", true))).first() != null;
    }

    public MedicoResponse cadastrar(MedicoResponse medico) {
        return MongoErros.traduzir(() -> {
            col.insertOne(documento(medico).append("_id", new ObjectId(medico.id())));
            return medico;
        }, "CRM já cadastrado nesta UF");
    }

    public Optional<MedicoResponse> atualizar(MedicoResponse medico) {
        return MongoErros.traduzir(() -> {
            var resultado = col.updateOne(Filters.eq("_id", new ObjectId(medico.id())),
                    new Document("$set", documento(medico)));
            return resultado.getMatchedCount() == 0 ? Optional.empty() : Optional.of(medico);
        }, "CRM já cadastrado nesta UF");
    }

    public Optional<MedicoResponse> consultar(ObjectId id) {
        return Optional.ofNullable(col.find(Filters.eq("_id", id)).first()).map(this::resposta);
    }

    public List<MedicoResponse> listar() {
        return col.find().sort(ascending("nome", "_id")).map(this::resposta).into(new ArrayList<>());
    }

    private Document documento(MedicoResponse medico) {
        return new Document("nome", medico.nome()).append("crmNumero", medico.crmNumero())
                .append("crmUf", medico.crmUf()).append("ativo", medico.ativo())
                .append("especialidadeIds", medico.especialidadeIds().stream().map(ObjectId::new).toList());
    }

    private MedicoResponse resposta(Document d) {
        return new MedicoResponse(d.getObjectId("_id").toHexString(), d.getString("nome"),
                d.getString("crmNumero"), d.getString("crmUf"),
                d.getList("especialidadeIds", ObjectId.class).stream().map(ObjectId::toHexString).toList(),
                d.getBoolean("ativo"));
    }
}
