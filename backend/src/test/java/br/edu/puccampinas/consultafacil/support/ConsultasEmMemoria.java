package br.edu.puccampinas.consultafacil.support;

import br.edu.puccampinas.consultafacil.repository.ConsultaRepository;
import br.edu.puccampinas.consultafacil.repository.PendenciasConsultaRepository;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Coleção `consultas` em memória, com a mesma checagem de versão do ConsultaRepository. Para testes sem banco. */
public class ConsultasEmMemoria implements PendenciasConsultaRepository {
    public final Map<ObjectId, Document> docs = new TreeMap<>();

    /** Grava direto, sem evento, como se a consulta já existisse no banco. */
    public void salvar(Document consulta) { docs.put(consulta.getObjectId("_id"), copia(consulta)); }

    @Override public Optional<Document> buscar(ObjectId id) { return Optional.ofNullable(docs.get(id)).map(ConsultasEmMemoria::copia); }

    @Override public Document inserirComEvento(Document d, Document e) {
        Document salvo = copia(d).append("versao", 1).append("eventosPendentes", new ArrayList<>(List.of(copia(e))));
        docs.put(d.getObjectId("_id"), salvo);
        return copia(salvo);
    }

    @Override public boolean atualizarComEvento(ObjectId id, int versao, Document campos, Document evento) {
        Document d = docs.get(id);
        if (d == null || d.getInteger("versao", 0) != versao) return false;
        List<Document> fila = new ArrayList<>(d.getList("eventosPendentes", Document.class, List.of()));
        if (fila.size() >= ConsultaRepository.LIMITE_PENDENCIAS) return false;
        d.putAll(campos);
        d.put("versao", evento.getInteger("sequencia"));
        fila.add(copia(evento));
        d.put("eventosPendentes", fila);
        return true;
    }

    @Override public List<Document> pendentesApos(ObjectId cursor, int limite) {
        return docs.values().stream().filter(d -> cursor == null || d.getObjectId("_id").compareTo(cursor) > 0)
                .filter(d -> !d.getList("eventosPendentes", Document.class, List.of()).isEmpty())
                .limit(limite).map(ConsultasEmMemoria::copia).toList();
    }

    @Override public void removerPendencia(ObjectId consulta, ObjectId evento) {
        List<Document> fila = new ArrayList<>(docs.get(consulta).getList("eventosPendentes", Document.class, List.of()));
        fila.removeIf(e -> e.getObjectId("_id").equals(evento));
        docs.get(consulta).put("eventosPendentes", fila);
    }

    private static Document copia(Document d) { return Document.parse(d.toJson()); }
}
