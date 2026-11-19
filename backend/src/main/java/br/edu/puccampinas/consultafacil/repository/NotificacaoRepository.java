package br.edu.puccampinas.consultafacil.repository;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import java.util.Date;
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

    private Bson caixa(Perfil perfil, ObjectId destinatario) {
        return Filters.and(Filters.eq("destinatarioPerfil", perfil.name()), Filters.eq("destinatarioId", destinatario),
                Filters.eq("canal", "SISTEMA"), Filters.eq("status", "ENVIADA"));
    }

    public List<Document> entregues(Perfil perfil, ObjectId destinatario) {
        return col.find(caixa(perfil, destinatario)).sort(Sorts.descending("enviadaEm", "_id")).into(new ArrayList<>());
    }

    public Optional<Document> entregue(ObjectId id, Perfil perfil, ObjectId destinatario) {
        return Optional.ofNullable(col.find(Filters.and(caixa(perfil, destinatario), Filters.eq("_id", id))).first());
    }

    public void registrarLeitura(ObjectId id, Perfil perfil, ObjectId destinatario, Date agora, boolean confirmou) {
        var campos = new Document("lidaEm", new Document("$ifNull", List.of("$lidaEm", agora)));
        if (confirmou) campos.append("confirmadaEm", new Document("$ifNull", List.of("$confirmadaEm", agora)));
        var resultado = col.updateOne(Filters.and(caixa(perfil, destinatario), Filters.eq("_id", id)),
                List.of(new Document("$set", campos)));
        if (resultado.getMatchedCount() == 0) throw ApiException.notFound("Notificação não encontrada");
    }

    public List<Document> avisosDaConsulta(ObjectId origem) {
        return col.find(Filters.and(Filters.eq("consultaId", origem), Filters.eq("tipo", "VAGA_LIBERADA"),
                Filters.eq("canal", "SISTEMA"))).into(new ArrayList<>());
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
