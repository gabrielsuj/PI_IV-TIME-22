package br.edu.puccampinas.consultafacil.repository;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;

/** Gravação das notificações simuladas. O envio/exibição fica para quem consome a coleção (#57). */
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

    /** Descarta uma notificação ainda não enviada (status AGENDADA -> CANCELADA). */
    public void descartar(ObjectId id) {
        col.updateOne(Filters.and(Filters.eq("_id", id), Filters.eq("status", "AGENDADA")),
                Updates.set("status", "CANCELADA"));
    }
}
