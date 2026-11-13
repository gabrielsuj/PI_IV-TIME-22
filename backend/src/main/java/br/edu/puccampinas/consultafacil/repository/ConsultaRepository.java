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

public class ConsultaRepository implements PendenciasConsultaRepository {
    public static final int LIMITE_PENDENCIAS = 50;
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

    /** Consultas ativas do médico que começam a partir do instante informado, em ordem de início (#44). */
    public List<Document> ativasAPartirDe(ObjectId medicoId, Date inicio) {
        return col.find(Filters.and(
                        Filters.eq("medicoId", medicoId),
                        Filters.in("status", StatusConsulta.ATIVOS),
                        Filters.gte("inicio", inicio)))
                .sort(Sorts.ascending("inicio"))
                .into(new ArrayList<>());
    }

    /** Todas as consultas do médico (qualquer status) que tocam a janela, em ordem de início. Usada pelo calendário (#23). */
    public List<Document> doMedicoNoPeriodo(ObjectId medicoId, Date inicio, Date fim) {
        return col.find(Filters.and(
                        Filters.eq("medicoId", medicoId),
                        Filters.lt("inicio", fim),
                        Filters.gt("fim", inicio)))
                .sort(Sorts.ascending("inicio"))
                .into(new ArrayList<>());
    }

    /** Listagem de consultas (#30): filtro montado pelo serviço, ordenada por início e limitada. */
    public List<Document> listar(org.bson.conversions.Bson filtro, boolean crescente, int limite) {
        return col.find(filtro)
                .sort(crescente ? Sorts.ascending("inicio", "_id") : Sorts.descending("inicio", "_id"))
                .limit(limite)
                .into(new ArrayList<>());
    }

    @Override public java.util.Optional<Document> buscar(ObjectId id) {
        return java.util.Optional.ofNullable(col.find(Filters.eq("_id", id)).first());
    }

    @Override public Document inserirComEvento(Document consulta, Document evento) {
        Document documento = new Document(consulta)
                .append("versao", 1).append("eventosPendentes", List.of(evento));
        return inserir(documento);
    }

    @Override public boolean atualizarComEvento(ObjectId id, int versao, Document campos, Document evento) {
        // Documentos antigos sem versão equivalem à versão zero.
        var versaoEsperada = versao == 0
                ? Filters.or(Filters.exists("versao", false), Filters.eq("versao", 0))
                : Filters.eq("versao", versao);
        var filtro = Filters.and(Filters.eq("_id", id), versaoEsperada,
                Filters.exists("eventosPendentes." + (LIMITE_PENDENCIAS - 1), false));
        Document update = new Document("$set", new Document(campos).append("versao", evento.getInteger("sequencia")))
                .append("$push", new Document("eventosPendentes", evento));
        return MongoErros.traduzir(() -> col.updateOne(filtro, update).getMatchedCount() == 1,
                "Horário indisponível: já existe consulta ativa para o médico ou para o paciente");
    }

    @Override public List<Document> pendentesApos(ObjectId depoisDe, int limite) {
        var filtro = depoisDe == null ? Filters.exists("eventosPendentes.0", true)
                : Filters.and(Filters.exists("eventosPendentes.0", true), Filters.gt("_id", depoisDe));
        return col.find(filtro).sort(Sorts.ascending("_id")).limit(limite).into(new ArrayList<>());
    }

    @Override public void removerPendencia(ObjectId consultaId, ObjectId eventoId) {
        col.updateOne(Filters.eq("_id", consultaId),
                new Document("$pull", new Document("eventosPendentes", new Document("_id", eventoId))));
    }
}
