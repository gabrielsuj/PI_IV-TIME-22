package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.dto.PacienteRequest;
import br.edu.puccampinas.consultafacil.domain.exception.ValidacaoException;
import java.util.Locale;
import java.util.regex.Pattern;

public class PacienteValidator {
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9!#$%&'*+/=?^_{|}~.-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$");

    public PacienteRequest validar(PacienteRequest request) {
        if (request == null) throw new ValidacaoException("Informe os dados do paciente.");
        String nome = obrigatorio(request.nome(), "Nome");
        String cpf = obrigatorio(request.cpf(), "CPF");
        String email = obrigatorio(request.email(), "E-mail").toLowerCase(Locale.ROOT);
        if (!cpf.matches("[0-9]{11}|[0-9]{3}\\.[0-9]{3}\\.[0-9]{3}-[0-9]{2}"))
            throw new ValidacaoException("CPF inválido. Informe 11 dígitos ou use 000.000.000-00.");
        cpf = cpf.replace(".", "").replace("-", "");
        if (cpf.chars().distinct().count() == 1 || !digitosValidos(cpf))
            throw new ValidacaoException("CPF inválido: confira os dígitos verificadores.");
        String local = email.substring(0, Math.max(0, email.indexOf('@')));
        if (email.length() > 254 || local.length() > 64 || !EMAIL.matcher(email).matches()
                || local.startsWith(".") || local.endsWith(".") || local.contains(".."))
            throw new ValidacaoException("E-mail inválido. Exemplo: nome@dominio.com.");
        return new PacienteRequest(nome, cpf, email);
    }

    private String obrigatorio(String valor, String campo) {
        if (valor == null || valor.isBlank()) throw new ValidacaoException(campo + " é obrigatório.");
        return valor.strip();
    }

    private boolean digitosValidos(String cpf) {
        for (int tamanho = 9; tamanho <= 10; tamanho++) {
            int soma = 0;
            for (int i = 0; i < tamanho; i++) soma += (cpf.charAt(i) - '0') * (tamanho + 1 - i);
            int digito = (soma * 10) % 11;
            if (digito == 10) digito = 0;
            if (digito != cpf.charAt(tamanho) - '0') return false;
        }
        return true;
    }
}
