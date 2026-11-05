package br.edu.puccampinas.consultafacil.repository;

import br.edu.puccampinas.consultafacil.domain.enums.StatusConsulta;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ConsultaRepository {
    private final MongoCollection<Document> col;

    public ConsultaRepository(MongoDatabase db) { this.col = db.getCollection("consultas"); }

    /** Grava a consulta. Se a vaga do médico ou o horário do paciente já estiver ocupado lança 409. */
    public Document inserir(Document d) {
        return MongoErros.traduzir(() -> { col.insertOne(d); return d; },
                "Horário indisponível: já existe consulta ativa para o médico ou para o paciente");
    }

    /** Consultas ativas que tocam a janela. */
    public List<Document> ativasNoPeriodo(ObjectId medicoId, Date inicio, Date fim) {
        return col.find(Filters.and(
                        Filters.eq("medicoId", medicoId),
                        Filters.in("status", StatusConsulta.ATIVOS),
                        Filters.lt("inicio", fim),
                        Filters.gt("fim", inicio)))
                .sort(Sorts.ascending("inicio"))
                .into(new ArrayList<>());
    }
}