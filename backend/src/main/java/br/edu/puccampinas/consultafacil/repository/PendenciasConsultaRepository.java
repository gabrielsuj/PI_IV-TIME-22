package br.edu.puccampinas.consultafacil.repository;

import org.bson.Document;
import org.bson.types.ObjectId;
import java.util.List;
import java.util.Optional;

/** Persistência da consulta e de sua caixa de eventos; não conhece HTTP ou sessões. */
public interface PendenciasConsultaRepository {
    Optional<Document> buscar(ObjectId id);
    Document inserirComEvento(Document consulta, Document evento);
    boolean atualizarComEvento(ObjectId id, int versao, Document campos, Document evento);
    List<Document> pendentesApos(ObjectId depoisDe, int limite);
    void removerPendencia(ObjectId consultaId, ObjectId eventoId);
}
