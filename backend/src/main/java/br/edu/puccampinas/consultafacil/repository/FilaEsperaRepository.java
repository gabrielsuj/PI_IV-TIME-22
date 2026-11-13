package br.edu.puccampinas.consultafacil.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Leitura e sinalização da fila de espera (#32). O cadastro dos interesses é da #31.
 * Regras de compatibilidade: docs/modelagem/filaEspera.md.
 */
public class FilaEsperaRepository {
    private final MongoCollection<Document> col;

    public FilaEsperaRepository(MongoDatabase db) { this.col = db.getCollection("filaEspera"); }

    /**
     * Interesses AGUARDANDO, ainda válidos, da especialidade, para o médico da vaga ou para qualquer médico,
     * cuja janela (dataInicio/dataFim, quando informadas) contém o início da vaga. Ordem de chegada (criadoEm).
     * O turno e a sobreposição com outras consultas do paciente são conferidos no serviço.
     */
    public List<Document> candidatos(ObjectId especialidadeId, ObjectId medicoId, Date inicioVaga, Date agora) {
        return col.find(Filters.and(
                        Filters.eq("status", "AGUARDANDO"),
                        Filters.eq("especialidadeId", especialidadeId),
                        Filters.in("medicoId", Arrays.asList(medicoId, null)),
                        Filters.gt("validoAte", agora),
                        Filters.or(Filters.exists("dataInicio", false), Filters.lte("dataInicio", inicioVaga)),
                        Filters.or(Filters.exists("dataFim", false), Filters.gte("dataFim", inicioVaga))))
                .sort(Sorts.ascending("criadoEm", "_id"))
                .into(new ArrayList<>());
    }

    /**
     * AGUARDANDO -> SINALIZADA. Condicional: se o interesse mudou nesse meio-tempo (cancelado, já sinalizado
     * por outra vaga), nada é alterado e retorna false.
     */
    public boolean marcarSinalizada(ObjectId id, Document ultimaSinalizacao, Date agora) {
        return col.updateOne(
                Filters.and(Filters.eq("_id", id), Filters.eq("status", "AGUARDANDO")),
                Updates.combine(
                        Updates.set("status", "SINALIZADA"),
                        Updates.set("ultimaSinalizacao", ultimaSinalizacao),
                        Updates.set("atualizadoEm", agora)))
                .getModifiedCount() == 1;
    }
}
