package br.edu.puccampinas.consultafacil.api.dto;

import org.bson.Document;

/** Consulta devolvida pela API. inicio e fim em ISO 8601 UTC. */
public record ConsultaResponse(String id, String pacienteId, String medicoId, String especialidadeId,
                               String inicio, String fim, String status) {

    /** Converte o documento de `consultas` sem expor campos internos (versao, eventosPendentes). */
    public static ConsultaResponse de(Document d) {
        return new ConsultaResponse(
                d.getObjectId("_id").toHexString(),
                d.getObjectId("pacienteId").toHexString(),
                d.getObjectId("medicoId").toHexString(),
                d.getObjectId("especialidadeId").toHexString(),
                d.getDate("inicio").toInstant().toString(),
                d.getDate("fim").toInstant().toString(),
                d.getString("status"));
    }
}
