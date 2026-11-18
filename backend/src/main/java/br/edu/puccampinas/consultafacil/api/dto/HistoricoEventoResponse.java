package br.edu.puccampinas.consultafacil.api.dto;

import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/** Evento da #46 e seus detalhes específicos. Datas em UTC; ocorridoEm é diferente do horário reservado. */
public record HistoricoEventoResponse(String id, int sequencia, String evento,
                                      String statusAnterior, String statusNovo, String ocorridoEm,
                                      String dataHoraConsulta, String fimConsulta, HistoricoAtorResponse ator,
                                      List<HistoricoAlteracaoResponse> alteracoes, String motivo, Integer totalSinalizados) {
    public HistoricoEventoResponse {
        if (alteracoes != null) alteracoes = List.copyOf(alteracoes);
    }

    public static HistoricoEventoResponse de(Document documento) {
        String tipo = documento.getString("evento");
        List<HistoricoAlteracaoResponse> alteracoes = "ALTERACAO".equals(tipo)
                ? documento.getList("alteracoes", Document.class, List.of()).stream()
                    .map(HistoricoAlteracaoResponse::de).flatMap(Optional::stream).toList() : null;
        return new HistoricoEventoResponse(id(documento.getObjectId("_id")), documento.getInteger("sequencia"),
                tipo, documento.getString("statusAnterior"), documento.getString("statusNovo"),
                data(documento.getDate("ocorridoEm")), data(documento.getDate("dataHoraConsulta")), data(documento.getDate("fimConsulta")),
                HistoricoAtorResponse.de(documento.get("ator", Document.class)), alteracoes,
                "CANCELAMENTO".equals(tipo) ? documento.getString("motivo") : null,
                "SINALIZACAO_FILA".equals(tipo) ? documento.getInteger("totalSinalizados") : null);
    }

    private static String id(ObjectId id) { return id == null ? null : id.toHexString(); }
    private static String data(Date data) { return data == null ? null : data.toInstant().toString(); }
}
