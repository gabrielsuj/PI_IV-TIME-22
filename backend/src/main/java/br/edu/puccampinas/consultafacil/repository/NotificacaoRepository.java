package br.edu.puccampinas.consultafacil.repository;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Notificações simuladas e avisos de vaga no sistema. */
public class NotificacaoRepository {
    private final MongoCollection<Document> col;

    public NotificacaoRepository(MongoDatabase db) { this.col = db.getCollection("notificacoes"); }

    /**
     * Insere a notificação. Retorna false se ela já existia (índice uq_notif_idempotencia:
     * mesma consulta, tipo, destinatário e canal), o que torna o reprocessamento seguro.
     */
    public boolean inserirSeNova(Document notificacao) {
        try {
            col.insertOne(notificacao);
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == 11000) return false;
            throw e;
        }
    }

    public List<Document> avisosDeVaga(ObjectId pacienteId) {
        return col.find(avisosDoPaciente(pacienteId))
                .sort(Sorts.descending("criadoEm", "_id")).into(new ArrayList<>());
    }

    public Optional<Document> buscarAvisoDeVaga(ObjectId id, ObjectId pacienteId) {
        return Optional.ofNullable(col.find(Filters.and(Filters.eq("_id", id), avisosDoPaciente(pacienteId))).first());
    }

    private static Bson avisosDoPaciente(ObjectId pacienteId) {
        return Filters.and(Filters.eq("destinatarioPerfil", "PACIENTE"), Filters.eq("destinatarioId", pacienteId),
                Filters.eq("tipo", "VAGA_LIBERADA"), Filters.eq("canal", "SISTEMA"));
    }

    /** Descarta uma notificação ainda não enviada (status AGENDADA -> CANCELADA). */
    public void descartar(ObjectId id) {
        col.updateOne(Filters.and(Filters.eq("_id", id), Filters.eq("status", "AGENDADA")),
                Updates.set("status", "CANCELADA"));
    }
}
