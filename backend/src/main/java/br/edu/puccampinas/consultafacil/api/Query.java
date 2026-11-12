package br.edu.puccampinas.consultafacil.api;

import com.sun.net.httpserver.HttpExchange;
import org.bson.types.ObjectId;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Leitura dos parâmetros da query string */
public final class Query {
    private Query() {}

    public static Map<String, String> params(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null || q.isBlank()) return m;
        for (String par : q.split("&")) {
            String[] kv = par.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }

    public static String obrigatorio(HttpExchange ex, String nome) {
        String v = params(ex).get(nome);
        if (v == null || v.isBlank()) throw ApiException.badRequest("Parâmetro obrigatório: " + nome);
        return v;
    }

    /** ?confirmar=true: o usuário já viu as consultas afetadas e quer seguir mesmo assim (#44). */
    public static boolean confirmado(HttpExchange ex) {
        return "true".equalsIgnoreCase(params(ex).get("confirmar"));
    }

    public static ObjectId objectId(String valor, String nome) {
        if (valor == null || !ObjectId.isValid(valor)) throw ApiException.badRequest(nome + " inválido");
        return new ObjectId(valor);
    }
}
