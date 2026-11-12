package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;

final class CadastroValidator {
    private CadastroValidator() {}

    static String texto(String valor, String campo, int minimo, int maximo) {
        if (valor == null) throw ApiException.badRequest(campo + " é obrigatório");
        String texto = valor.strip();
        int tamanho = texto.codePointCount(0, texto.length());
        if (tamanho < minimo || tamanho > maximo)
            throw ApiException.badRequest(campo + " deve ter de " + minimo + " a " + maximo + " caracteres");
        return texto;
    }

    static String formato(String valor, String campo, String padrao) {
        if (valor == null || !valor.matches(padrao)) throw ApiException.badRequest(campo + " inválido");
        return valor;
    }
}
