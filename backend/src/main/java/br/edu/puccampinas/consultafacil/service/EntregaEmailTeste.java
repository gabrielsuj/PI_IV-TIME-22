/*
Autoria: Murilo Moraes
RA: 25000073
*/
package br.edu.puccampinas.consultafacil.service;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Canal EMAIL_TESTE das notificações simuladas (#34). Nada sai da máquina: o "envio" é uma linha no log
 * do servidor, com o endereço mascarado. Lança exceção quando não há para onde enviar; o agendador
 * conta a tentativa e, na terceira, marca a notificação como FALHA.
 */
@FunctionalInterface
public interface EntregaEmailTeste {
    void entregar(Document notificacao);

    /** Busca o e-mail do paciente destinatário e registra a mensagem no log. */
    final class NoLog implements EntregaEmailTeste {
        private static final Logger LOG = LoggerFactory.getLogger("email-teste");
        private final MongoDatabase db;

        public NoLog(MongoDatabase db) { this.db = db; }

        @Override
        public void entregar(Document n) {
            if (!"PACIENTE".equals(n.getString("destinatarioPerfil")))
                throw new IllegalStateException("E-mail de teste só é enviado a pacientes");
            ObjectId id = n.getObjectId("destinatarioId");
            Document paciente = db.getCollection("pacientes").find(Filters.eq("_id", id))
                    .projection(Projections.include("email")).first();
            String email = paciente == null ? null : paciente.getString("email");
            if (email == null || email.isBlank()) throw new IllegalStateException("Paciente sem e-mail cadastrado");
            LOG.info("[e-mail de teste] para {} | {} | {}", mascarar(email), n.getString("titulo"), n.getString("mensagem"));
        }

        /** "paulo.silva@gmail.com" -> "p***@gmail.com": o log não guarda o endereço completo. */
        public static String mascarar(String email) {
            int arroba = email.indexOf('@');
            if (arroba <= 0) return "***";
            return email.charAt(0) + "***" + email.substring(arroba);
        }
    }
}
