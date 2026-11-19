package br.edu.puccampinas.consultafacil.api.dto;

import br.edu.puccampinas.consultafacil.api.JsonComNulos;
import com.google.gson.annotations.JsonAdapter;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import java.util.Date;
import java.util.Optional;
import java.util.Set;

/** Valores escalares JSON: referências em texto, datas UTC e null para ausência anterior/atual de valor. */
@JsonAdapter(JsonComNulos.class)
public record HistoricoAlteracaoResponse(String campo, Object valorAnterior, Object valorNovo) {
    private static final Set<String> CAMPOS = Set.of("pacienteId", "medicoId", "especialidadeId", "inicio", "fim",
            "dataHora", "dataHoraConsulta", "status", "motivoCancelamento");
    private static final Object INVALIDO = new Object();

    /** Campos desconhecidos ou valores compostos não são publicados como detalhes da reserva. */
    public static Optional<HistoricoAlteracaoResponse> de(Document documento) {
        Object campo = documento.get("campo");
        if (!(campo instanceof String nome) || !CAMPOS.contains(nome)) return Optional.empty();
        if (!documento.containsKey("valorAnterior") || !documento.containsKey("valorNovo")) return Optional.empty();
        Object anterior = valor(documento.get("valorAnterior")), novo = valor(documento.get("valorNovo"));
        if (anterior == INVALIDO || novo == INVALIDO) return Optional.empty();
        return Optional.of(new HistoricoAlteracaoResponse(nome, anterior, novo));
    }

    private static Object valor(Object valor) {
        if (valor instanceof ObjectId id) return id.toHexString();
        if (valor instanceof Date data) return data.toInstant().toString();
        if (valor instanceof Decimal128 decimal) {
            try { return decimal.bigDecimalValue(); }
            catch (ArithmeticException e) { return INVALIDO; }
        }
        if (valor instanceof Double realDouble && !Double.isFinite(realDouble)
                || valor instanceof Float realFloat && !Float.isFinite(realFloat)) return INVALIDO;
        return valor == null || valor instanceof String || valor instanceof Number ? valor : INVALIDO;
    }
}
