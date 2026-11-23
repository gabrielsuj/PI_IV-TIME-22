
package br.edu.puccampinas.consultafacil.domain.exception;

public class ServidorCpfIndisponivelException extends RuntimeException {

    public ServidorCpfIndisponivelException(Throwable causa) {
        super("Servidor de CPF indisponível.", causa);
    }
}
