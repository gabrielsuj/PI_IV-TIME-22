package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.repository.HistoricoRepository;
import br.edu.puccampinas.consultafacil.repository.PendenciasConsultaRepository;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Executado pelo chamador, sem thread, agendador ou conexão própria. */
public final class HistoricoProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(HistoricoProcessor.class);
    private final PendenciasConsultaRepository consultas;
    private final HistoricoRepository historico;
    private ObjectId cursorAutomatico;

    public record Lote(ObjectId proximoCursor, int consultasLidas, int eventosProcessados, int falhas) {}

    public HistoricoProcessor(PendenciasConsultaRepository consultas, HistoricoRepository historico) {
        this.consultas = consultas;
        this.historico = historico;
    }

    public int processarConsulta(ObjectId id) {
        try {
            return consultas.buscar(id).map(d -> transferir(d).processados()).orElse(0);
        } catch (RuntimeException e) {
            LOG.warn("Pendência de histórico preservada para consulta {} ({})", id, e.getClass().getSimpleName());
            return 0;
        }
    }

    public int ultimaSequencia(ObjectId consultaId) { return historico.ultimaSequencia(consultaId); }

    private record Transferencia(int processados, boolean falhou) {}

    private Transferencia transferir(Document consulta) {
        List<Document> eventos = new ArrayList<>(consulta.getList("eventosPendentes", Document.class, List.of()));
        eventos.sort(Comparator.comparingInt(e -> e.getInteger("sequencia")));
        int processados = 0;
        for (Document evento : eventos) {
            try {
                historico.registrar(evento);
                // Nunca remover antes da confirmação; $pull remove só este evento.
                consultas.removerPendencia(consulta.getObjectId("_id"), evento.getObjectId("_id"));
                processados++;
            } catch (RuntimeException e) {
                LOG.warn("Evento {} da consulta {} permanece pendente ({})", evento.getObjectId("_id"),
                        consulta.getObjectId("_id"), e.getClass().getSimpleName());
                return new Transferencia(processados, true);
            }
        }
        return new Transferencia(processados, false);
    }

    /** Paginação por _id: uma consulta inválida não monopoliza o primeiro lote. */
    public Lote recuperarLote(ObjectId depoisDe, int limite) {
        if (limite < 1 || limite > 100) throw new IllegalArgumentException("Lote deve conter de 1 a 100 consultas");
        List<Document> lote = consultas.pendentesApos(depoisDe, limite);
        int processados = 0, falhas = 0;
        for (Document consulta : lote) {
            try {
                Transferencia resultado = transferir(consulta);
                processados += resultado.processados();
                if (resultado.falhou()) falhas++;
            }
            catch (RuntimeException e) {
                falhas++;
                LOG.warn("Recuperação pendente para consulta {} ({})", consulta.getObjectId("_id"), e.getClass().getSimpleName());
            }
        }
        ObjectId cursor = lote.size() < limite ? null : lote.get(lote.size() - 1).getObjectId("_id");
        return new Lote(cursor, lote.size(), processados, falhas);
    }

    /** Uma tentativa por chamada; não há laço de retries enquanto o banco falha. */
    public synchronized void tentarRecuperarLote() {
        try { cursorAutomatico = recuperarLote(cursorAutomatico, 5).proximoCursor(); }
        catch (RuntimeException e) {
            LOG.warn("Recuperação de histórico adiada ({})", e.getClass().getSimpleName());
        }
    }
}
