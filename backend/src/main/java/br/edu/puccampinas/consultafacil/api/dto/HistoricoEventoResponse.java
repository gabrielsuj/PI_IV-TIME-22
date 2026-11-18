package br.edu.puccampinas.consultafacil.api.dto;

import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.Date;

/** Campos comuns do evento (#46). Datas em UTC; ocorridoEm é diferente do horário reservado. */
public record HistoricoEventoResponse(String id, int sequencia, String evento,
                                      String statusAnterior, String statusNovo, String ocorridoEm,
                                      String dataHoraConsulta, String fimConsulta, HistoricoAtorResponse ator) {
    public static HistoricoEventoResponse de(Document documento) {
        Document ator = documento.get("ator", Document.class);
        return new HistoricoEventoResponse(id(documento.getObjectId("_id")), documento.getInteger("sequencia"),
                documento.getString("evento"), documento.getString("statusAnterior"), documento.getString("statusNovo"),
                data(documento.getDate("ocorridoEm")), data(documento.getDate("dataHoraConsulta")), data(documento.getDate("fimConsulta")),
                ator == null ? null : new HistoricoAtorResponse(ator.getString("perfil"), id(ator.getObjectId("id"))));
    }

    private static String id(ObjectId id) { return id == null ? null : id.toHexString(); }
    private static String data(Date data) { return data == null ? null : data.toInstant().toString(); }
}
