package br.edu.puccampinas.consultafacil.repository;

import br.edu.puccampinas.consultafacil.api.ApiException;
import com.mongodb.MongoServerException;

import java.util.function.Supplier;

/** Traduz recusas do MongoDB para respostas HTTP */
final class MongoErros {
    private MongoErros() {}

    static <T> T traduzir(Supplier<T> op) {
        return traduzir(op, "Registro duplicado");
    }

    static <T> T traduzir(Supplier<T> op, String mensagemConflito) {
        try {
            return op.get();
        } catch (MongoServerException e) {
            if (e.getCode() == 11000) throw ApiException.conflict(mensagemConflito);
            if (e.getCode() == 121)   throw ApiException.badRequest("Dados recusados pela validação do banco");
            throw e;
        }
    }
}
