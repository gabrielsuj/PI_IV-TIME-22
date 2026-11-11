package br.edu.puccampinas.consultafacil.handler;

import br.edu.puccampinas.consultafacil.protocol.Comunicado;

public class ValidarCPFHandler extends Comunicado {
    public static ValidarCPFResponse ValidateCPF(ValidarCPFRequest request) {
        String cpf = request.getCPF();

        if(cpf == null) return new ValidarCPFResponse(null, false, "CPF não fornecido");
        if(cpf.chars().distinct().count() == 1)
            return new ValidarCPFResponse(cpf, false, "CPF com digitos repetidos são invalidos");

        int sum, peso, resto;
        char digito10, digito11;

        // Calculate o 10° digito
        sum = 0; peso = 10;
        for(int i = 0; i < 9; i++) {
            // Números na tabela ASCII começam em 48
            sum += (int)(cpf.charAt(i) - 48) * peso;
            peso--;
        }

        resto = 11 - (sum % 11);
        if(resto == 10 || resto == 11) digito10 = '0';
        else digito10 = (char)(resto + 48);

        // Calculate o 11° digito, incluindo o 10° digito no cálculo
        sum = 0; peso = 11;
        for(int i = 0; i < 10; i++) {
            sum += (int)(cpf.charAt(i) - 48) * peso;
            peso--;
        }

        resto = 11 - (sum % 11);
        if(resto == 10 || resto == 11) digito11 = '0';
        else digito11 = (char)(resto + 48);

        // Finalmente verifica se o CPF é de fato válido
        if(cpf.charAt(9) == digito10 && cpf.charAt(10) == digito11) {
            return new ValidarCPFResponse(cpf, true, "OK");
        } else {
            return new ValidarCPFResponse(cpf, false, "CPF não é válido.");
        }

    }
}
