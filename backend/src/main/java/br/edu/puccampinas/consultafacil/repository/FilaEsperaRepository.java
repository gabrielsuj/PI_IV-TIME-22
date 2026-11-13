package br.edu.puccampinas.consultafacil.repository;

import com.mongodb.client.MongoCollection;
import org.bson.conversions.Bson;
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
import java.util.Optional;

/**
 * Inscrições na fila de espera: cadastro, consulta e remoção (#31) e sinalização após cancelamento (#32).
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

    // ------------------------------------------------------------------ cadastro (#31)

    /** Status que contam como inscrição ativa (índice único parcial uq_fila_interesse_ativo). */
    public static final List<String> ATIVOS = List.of("AGUARDANDO", "SINALIZADA");

    /** Insere a inscrição. 409 se o paciente já tem inscrição ativa igual (uq_fila_interesse_ativo). */
    public Document inserir(Document inscricao) {
        return MongoErros.traduzir(() -> { col.insertOne(inscricao); return inscricao; },
                "Você já está na fila para esta especialidade e este médico");
    }

    public Optional<Document> buscar(ObjectId id) {
        return Optional.ofNullable(col.find(Filters.eq("_id", id)).first());
    }

    /** Inscrições que atendem ao filtro, em ordem de chegada. filtro null = todas. */
    public List<Document> listar(Bson filtro) {
        return (filtro == null ? col.find() : col.find(filtro))
                .sort(Sorts.ascending("criadoEm", "_id"))
                .into(new ArrayList<>());
    }

    /** Ativa -> CANCELADA. Condicional: false se a inscrição já estava encerrada. */
    public boolean cancelar(ObjectId id, Date agora) {
        return col.updateOne(
                Filters.and(Filters.eq("_id", id), Filters.in("status", ATIVOS)),
                Updates.combine(
                        Updates.set("status", "CANCELADA"),
                        Updates.set("ultimaSinalizacao", null),
                        Updates.set("atualizadoEm", agora)))
                .getModifiedCount() == 1;
    }

    /**
     * Ativas com validoAte vencido -> EXPIRADA. Feita antes de listar e de inscrever (não há rotina agendada no MVP),
     * para a inscrição vencida não aparecer como ativa nem bloquear uma nova pelo índice único.
     */
    public long expirarVencidas(Date agora) {
        return col.updateMany(
                Filters.and(Filters.in("status", ATIVOS), Filters.lte("validoAte", agora)),
                Updates.combine(
                        Updates.set("status", "EXPIRADA"),
                        Updates.set("ultimaSinalizacao", null),
                        Updates.set("atualizadoEm", agora)))
                .getModifiedCount();
    }

    // ------------------------------------------------------------------ vaga ocupada / vencida

    /** Inscrições SINALIZADA para a vaga (médico + início) informada. */
    public List<Document> sinalizadasParaVaga(ObjectId medicoId, Date inicioVaga) {
        return col.find(Filters.and(
                        Filters.eq("status", "SINALIZADA"),
                        Filters.eq("ultimaSinalizacao.medicoId", medicoId),
                        Filters.eq("ultimaSinalizacao.dataHora", inicioVaga)))
                .sort(Sorts.ascending("criadoEm", "_id"))
                .into(new ArrayList<>());
    }

    /** SINALIZADA -> ATENDIDA: o próprio interessado agendou a vaga. */
    public boolean marcarAtendida(ObjectId id, ObjectId consultaId, Date agora) {
        return col.updateOne(
                Filters.and(Filters.eq("_id", id), Filters.eq("status", "SINALIZADA")),
                Updates.combine(
                        Updates.set("status", "ATENDIDA"),
                        Updates.set("ultimaSinalizacao", null),
                        Updates.set("consultaAgendadaId", consultaId),
                        Updates.set("atendidaEm", agora),
                        Updates.set("atualizadoEm", agora)))
                .getModifiedCount() == 1;
    }

    /** SINALIZADA -> AGUARDANDO: a vaga não está mais disponível. criadoEm (posição na fila) não muda. */
    public boolean voltarParaAguardando(ObjectId id, Date agora) {
        return col.updateOne(
                Filters.and(Filters.eq("_id", id), Filters.eq("status", "SINALIZADA")),
                paraAguardando(agora))
                .getModifiedCount() == 1;
    }

    /**
     * Manutenção feita antes de ler a fila (não há rotina agendada no MVP):
     * sinalizações de vagas que já passaram voltam a AGUARDANDO e inscrições vencidas passam a EXPIRADA.
     */
    public void revisar(Date agora) {
        col.updateMany(
                Filters.and(Filters.eq("status", "SINALIZADA"), Filters.lte("ultimaSinalizacao.dataHora", agora)),
                paraAguardando(agora));
        expirarVencidas(agora);
    }

    private static Bson paraAguardando(Date agora) {
        return Updates.combine(
                Updates.set("status", "AGUARDANDO"),
                Updates.set("ultimaSinalizacao", null),
                Updates.set("atualizadoEm", agora));
    }
}
