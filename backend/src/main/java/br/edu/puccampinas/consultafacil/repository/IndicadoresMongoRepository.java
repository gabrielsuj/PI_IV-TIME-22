package br.edu.puccampinas.consultafacil.repository;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.domain.enums.StatusConsulta;
import br.edu.puccampinas.consultafacil.service.CalculadoraCapacidade;
import br.edu.puccampinas.consultafacil.service.CalculadoraConfirmacao;
import br.edu.puccampinas.consultafacil.service.CalculadoraPreenchimentoFila;
import br.edu.puccampinas.consultafacil.service.PeriodoIndicadores;
import com.mongodb.MongoException;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Projections.*;

/**
 * Fontes completas da #36, sem chamar listagens públicas ou rotinas de manutenção. Projeta somente
 * campos de cálculo. Inclui médicos inativos para permitir o recorte histórico; filtro inexistente
 * gera 404. A capacidade continua sendo uma estimativa pela configuração atual.
 * <p>Na visão por médico, a fila é selecionada por consultas do escopo, nunca pelo médico preferido
 * da inscrição. A visão global também verifica referências órfãs/legadas, que não são atribuíveis
 * a um médico com segurança. Não há filtro por atendidaEm ou pela data do evento de confirmação.</p>
 */
public final class IndicadoresMongoRepository implements IndicadoresLeituraRepository {
    /** Porta de leituras internas completas, separada do cálculo para testes sem banco. */
    public interface Fontes {
        List<ObjectId> medicos(Optional<ObjectId> medico);
        List<Document> consultas(PeriodoIndicadores periodo, Optional<ObjectId> medico);
        List<Document> agendas(Set<ObjectId> medicos);
        List<Document> historico(Set<ObjectId> consultas);
        List<Document> atendidas(Optional<ObjectId> medico, Set<ObjectId> consultasDoRecorte);
        List<Document> consultasPorIds(Set<ObjectId> ids);
        List<Document> bloqueios(ObjectId medico, Instant inicio, Instant fim);
        List<Document> ocupantes(ObjectId medico, Instant inicio, Instant fim);
    }

    private final Fontes fontes;
    public IndicadoresMongoRepository(MongoDatabase db) { this(new FontesMongo(Objects.requireNonNull(db))); }
    public IndicadoresMongoRepository(Fontes fontes) { this.fontes = Objects.requireNonNull(fontes); }

    @Override public Resumo ler(PeriodoIndicadores periodo, Optional<ObjectId> medico) {
        Objects.requireNonNull(periodo);
        Objects.requireNonNull(medico);
        try { return agregar(periodo, medico); }
        catch (MongoException e) { throw new ApiException(503, "Não foi possível ler os indicadores. Tente novamente."); }
    }

    private Resumo agregar(PeriodoIndicadores periodo, Optional<ObjectId> medico) {
        // Necessita revisão de consistência entre leituras da #36 (etapa 7).
        Set<ObjectId> medicos = new HashSet<>(fontes.medicos(medico));
        medico.ifPresent(id -> medicos.removeIf(outro -> !id.equals(outro)));
        if (medico.isPresent() && !medicos.contains(medico.get())) throw ApiException.notFound("Médico não encontrado");
        Map<ObjectId, Document> coorte = new LinkedHashMap<>();
        for (Document consulta : fontes.consultas(periodo, medico)) {
            var inicio = consulta.getDate("inicio").toInstant();
            ObjectId medicoDaConsulta = Objects.requireNonNull(consulta.getObjectId("medicoId"));
            if (inicio.isBefore(periodo.inicioInclusivo()) || !inicio.isBefore(periodo.fimExclusivo())
                    || medico.isPresent() && !medico.get().equals(medicoDaConsulta)) continue;
            if (!medicos.contains(medicoDaConsulta)) throw new IllegalStateException("Consulta sem cadastro de médico correspondente");
            adicionar(coorte, consulta);
        }
        var consultas = new ArrayList<>(coorte.values());
        var ids = Set.copyOf(coorte.keySet());
        Map<StatusConsulta, Long> totais = new EnumMap<>(StatusConsulta.class);
        for (Document consulta : consultas) totais.merge(StatusConsulta.valueOf(consulta.getString("status")), 1L, Math::addExact);
        var estados = new Consultas(totais.getOrDefault(StatusConsulta.AGENDADA, 0L), totais.getOrDefault(StatusConsulta.CONFIRMADA, 0L),
                totais.getOrDefault(StatusConsulta.CONCLUIDA, 0L), totais.getOrDefault(StatusConsulta.FALTA, 0L), totais.getOrDefault(StatusConsulta.CANCELADA, 0L));

        Map<ObjectId, Document> agendas = new HashMap<>();
        for (Document agenda : fontes.agendas(Set.copyOf(medicos))) {
            ObjectId id = Objects.requireNonNull(agenda.getObjectId("medicoId"));
            if (!medicos.contains(id)) continue;
            if (agendas.putIfAbsent(id, agenda) != null) throw new IllegalStateException("Mais de uma agenda para o mesmo médico");
        }
        long capacidade = 0, ocupadas = 0, semAgenda = 0;
        for (ObjectId id : medicos) {
            Document agenda = agendas.get(id);
            if (agenda == null) { semAgenda++; continue; }
            var grade = CalculadoraCapacidade.slotsNoPeriodo(agenda, periodo, List.of());
            if (grade.isEmpty()) continue;
            Instant inicio = grade.getFirst().inicio();
            Instant fim = grade.stream().map(v -> v.fim()).max(Comparator.naturalOrder()).orElseThrow();
            var calculada = CalculadoraCapacidade.calcular(Optional.of(agenda), periodo,
                    fontes.bloqueios(id, inicio, fim), fontes.ocupantes(id, inicio, fim));
            capacidade = Math.addExact(capacidade, calculada.capacidade());
            ocupadas = Math.addExact(ocupadas, calculada.vagasOcupadas());
        }
        var confirmacao = CalculadoraConfirmacao.calcular(periodo, medico, consultas, ids.isEmpty() ? List.of() : fontes.historico(ids));
        var inscricoes = medico.isPresent() && ids.isEmpty() ? List.<Document>of() : fontes.atendidas(medico, ids);
        Set<ObjectId> referencias = new HashSet<>();
        for (Document inscricao : inscricoes)
            if (inscricao.get("consultaAgendadaId") instanceof ObjectId id && !coorte.containsKey(id)) referencias.add(id);
        Map<ObjectId, Document> vinculadas = new LinkedHashMap<>(coorte);
        if (!referencias.isEmpty()) for (Document consulta : fontes.consultasPorIds(Set.copyOf(referencias))) adicionar(vinculadas, consulta);
        var fila = CalculadoraPreenchimentoFila.calcular(periodo, medico, new ArrayList<>(vinculadas.values()), inscricoes);
        long mantidas = consultas.size() - estados.canceladas();
        if (fila.consultasNaoCanceladas() != mantidas) throw new IllegalStateException("População da fila mudou durante a leitura dos indicadores");
        return new Resumo(estados, new Capacidade(capacidade, ocupadas, semAgenda),
                new Confirmacao(confirmacao.comConfirmacaoRegistrada(), confirmacao.consultasComProjecaoPendente(), confirmacao.consultasSemBaseHistorica()),
                new Fila(fila.preenchidasPelaFila(), fila.inscricoesComVinculoInvalido()));
    }

    private static void adicionar(Map<ObjectId, Document> destino, Document consulta) {
        ObjectId id = Objects.requireNonNull(consulta.getObjectId("_id"));
        Document anterior = destino.putIfAbsent(id, consulta);
        if (anterior != null && !anterior.equals(consulta)) throw new IllegalStateException("Snapshots divergentes da mesma consulta");
    }

    private static final class FontesMongo implements Fontes {
        /** Tamanho do $in, não limite da quantidade de documentos retornados. */
        private static final int TAMANHO_LOTE = 500;
        private static final Bson CONSULTA = include("_id", "medicoId", "inicio", "status", "eventosPendentes._id", "versao");
        private final MongoDatabase db;
        private FontesMongo(MongoDatabase db) { this.db = db; }
        private List<Document> buscar(String colecao, Bson filtro, Bson projecao) {
            return db.getCollection(colecao).find(filtro).projection(projecao).into(new ArrayList<>());
        }

        @Override public List<ObjectId> medicos(Optional<ObjectId> medico) {
            Set<String> colecoes = new HashSet<>(db.listCollectionNames().into(new ArrayList<>()));
            if (!colecoes.containsAll(Set.of("medicos", "consultas", "agendas", "bloqueios", "historico", "filaEspera")))
                throw new ApiException(503, "Fontes dos indicadores ainda não estão disponíveis.");
            return buscar("medicos", medico.<Bson>map(id -> eq("_id", id)).orElseGet(Document::new), include("_id"))
                    .stream().map(d -> Objects.requireNonNull(d.getObjectId("_id"))).toList();
        }
        @Override public List<Document> consultas(PeriodoIndicadores periodo, Optional<ObjectId> medico) {
            Bson janela = and(gte("inicio", Date.from(periodo.inicioInclusivo())), lt("inicio", Date.from(periodo.fimExclusivo())));
            return buscar("consultas", medico.<Bson>map(id -> and(eq("medicoId", id), janela)).orElse(janela), CONSULTA);
        }
        @Override public List<Document> agendas(Set<ObjectId> medicos) {
            return lotes(medicos, ids -> buscar("agendas", in("medicoId", ids), include("medicoId", "fusoHorario", "duracaoMinutos", "jornadas")));
        }
        @Override public List<Document> historico(Set<ObjectId> consultas) {
            return lotes(consultas, ids -> buscar("historico", and(in("consultaId", ids), in("evento", "CRIACAO", "CONFIRMACAO")),
                    fields(include("consultaId", "evento"), excludeId())));
        }
        @Override public List<Document> atendidas(Optional<ObjectId> medico, Set<ObjectId> consultas) {
            Bson projecao = include("_id", "status", "consultaAgendadaId");
            if (medico.isEmpty()) return buscar("filaEspera", eq("status", "ATENDIDA"), projecao);
            return lotes(consultas, ids -> buscar("filaEspera", and(eq("status", "ATENDIDA"), in("consultaAgendadaId", ids)), projecao));
        }
        @Override public List<Document> consultasPorIds(Set<ObjectId> ids) {
            return lotes(ids, lote -> buscar("consultas", in("_id", lote), CONSULTA));
        }
        private Bson sobrepostos(ObjectId medico, Instant inicio, Instant fim) {
            return and(eq("medicoId", medico), lt("inicio", Date.from(fim)), gt("fim", Date.from(inicio)));
        }
        @Override public List<Document> bloqueios(ObjectId medico, Instant inicio, Instant fim) {
            return buscar("bloqueios", sobrepostos(medico, inicio, fim), include("inicio", "fim"));
        }
        @Override public List<Document> ocupantes(ObjectId medico, Instant inicio, Instant fim) {
            return buscar("consultas", and(sobrepostos(medico, inicio, fim), ne("status", "CANCELADA")), include("inicio", "fim", "status"));
        }
        private List<Document> lotes(Set<ObjectId> ids, Function<List<ObjectId>, List<Document>> leitura) {
            List<ObjectId> ordenados = new ArrayList<>(ids);
            List<Document> todos = new ArrayList<>();
            for (int i = 0; i < ordenados.size(); i += TAMANHO_LOTE)
                todos.addAll(leitura.apply(ordenados.subList(i, Math.min(i + TAMANHO_LOTE, ordenados.size()))));
            return todos;
        }
    }
}
