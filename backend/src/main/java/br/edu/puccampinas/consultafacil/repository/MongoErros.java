package br.edu.puccampinas.consultafacil.repository;

import br.edu.puccampinas.consultafacil.api.ApiException;
import com.mongodb.MongoServerException;

import java.util.function.Supplier;

/** Traduz recusas do MongoDB para respostas HTTP (sem isto viram 500 no Router). */
final class MongoErros {
    private MongoErros() {}

    static <T> T traduzir(Supplier<T> op) {
        try {
            return op.get();
        } catch (MongoServerException e) {          // cobre MongoWriteException e MongoCommandException
            if (e.getCode() == 11000) throw ApiException.conflict("Registro duplicado");
            if (e.getCode() == 121)   throw ApiException.badRequest("Dados recusados pela validação do banco");
            throw e;
        }
    }
}
