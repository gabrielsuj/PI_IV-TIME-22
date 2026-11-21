package br.edu.puccampinas.consultafacil.repository;

import br.edu.puccampinas.consultafacil.api.ApiException;
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
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.function.Supplier;
/**
 * @author Gabriel (criador)
 * @author Murilo Moraes (modificador)
 * @author Murilo Rigoni (modificador)
 */

public class ConsultaRepository implements PendenciasConsultaRepository {
    public static final int LIMITE_PENDENCIAS = 50;
    public static final String SOBREPOSICAO_PACIENTE = "O paciente já tem uma consulta ativa que se sobrepõe a este horário.";
    public static final String SOBREPOSICAO_MEDICO = "O médico já tem uma consulta ativa neste período.";
    // Travas compartilhadas pelas instâncias do repositório no backend, com tamanho fixo (#25).
    private static final List<Object> TRAVAS_PACIENTE = IntStream.range(0, 256).mapToObj(i -> new Object()).toList();
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

    /** Consulta ativa que ocupa o mesmo início com o médico, garantido pelo índice de reservas. */
    public Optional<Document> ativaNaVaga(ObjectId medicoId, Date inicio) {
        return Optional.ofNullable(col.find(Filters.and(Filters.eq("medicoId", medicoId), Filters.eq("inicio", inicio),
                Filters.in("status", StatusConsulta.ATIVOS))).first());
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

    /** true se o paciente já tem consulta ativa que se sobrepõe à janela, com qualquer médico (#25, #32). */
    public boolean pacienteTemAtivaSobreposta(ObjectId pacienteId, Date inicio, Date fim) {
        return temSobreposicao("pacienteId", pacienteId, inicio, fim, null);
    }

    private boolean temSobreposicao(String campo, ObjectId pessoa, Date inicio, Date fim, ObjectId ignorar) {
        var filtros = new ArrayList<>(List.of(
                Filters.eq(campo, pessoa),
                Filters.in("status", StatusConsulta.ATIVOS),
                Filters.lt("inicio", fim),
                Filters.gt("fim", inicio)));
        if (ignorar != null) filtros.add(Filters.ne("_id", ignorar));
        return col.find(Filters.and(filtros)).limit(1).first() != null;
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

    public List<ObjectId> sinalizacoesPendentes(ObjectId depoisDe, int limite) {
        var filtro = Filters.exists("sinalizacaoFila");
        if (depoisDe != null) filtro = Filters.and(filtro, Filters.gt("_id", depoisDe));
        return col.find(filtro).projection(new Document("_id", 1)).sort(Sorts.ascending("_id"))
                .limit(limite).map(d -> d.getObjectId("_id")).into(new ArrayList<>());
    }

    public void concluirLimpezaFila(ObjectId id) {
        col.updateOne(Filters.and(Filters.eq("_id", id), Filters.eq("sinalizacaoFila", "LIMPAR")),
                new Document("$unset", new Document("sinalizacaoFila", "")));
    }

    @Override public java.util.Optional<Document> buscar(ObjectId id) {
        return java.util.Optional.ofNullable(col.find(Filters.eq("_id", id)).first());
    }

    @Override public Document inserirComEvento(Document consulta, Document evento) {
        Document documento = new Document(consulta)
                .append("versao", 1).append("eventosPendentes", List.of(evento));
        return gravarSemSobreposicao(evento, null, () -> inserir(documento));
    }

    @Override public boolean atualizarComEvento(ObjectId id, int versao, Document campos, Document evento) {
        boolean mudaReserva = campos.containsKey("pacienteId") || campos.containsKey("medicoId")
                || campos.containsKey("inicio") || campos.containsKey("fim");
        if (!mudaReserva || !StatusConsulta.ATIVOS.contains(evento.getString("statusNovo")))
            return gravarAlteracao(id, versao, campos, evento);
        return gravarSemSobreposicao(evento, id, () -> gravarAlteracao(id, versao, campos, evento));
    }

    private <T> T gravarSemSobreposicao(Document evento, ObjectId ignorar, Supplier<T> gravar) {
        ObjectId medico = evento.getObjectId("medicoId"), paciente = evento.getObjectId("pacienteId");
        Date inicio = evento.getDate("dataHoraConsulta"), fim = evento.getDate("fimConsulta");
        // Todas as gravações adquirem primeiro a trava do médico e depois a do paciente.
        synchronized (TravaAgenda.porMedico(medico)) {
            synchronized (TRAVAS_PACIENTE.get(Math.floorMod(paciente.hashCode(), TRAVAS_PACIENTE.size()))) {
                if (temSobreposicao("pacienteId", paciente, inicio, fim, ignorar))
                    throw ApiException.conflict(SOBREPOSICAO_PACIENTE);
                if (temSobreposicao("medicoId", medico, inicio, fim, ignorar))
                    throw ApiException.conflict(SOBREPOSICAO_MEDICO);
                return gravar.get();
            }
        }
    }

    private boolean gravarAlteracao(ObjectId id, int versao, Document campos, Document evento) {
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
