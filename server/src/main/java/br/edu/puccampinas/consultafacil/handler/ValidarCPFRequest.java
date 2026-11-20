// Davi José Bertuolo Vitoreti - 25004168

package br.edu.puccampinas.consultafacil.handler;

import br.edu.puccampinas.consultafacil.protocol.Comunicado;

public class ValidarCPFRequest extends Comunicado {
    private final String cpf;

    public ValidarCPFRequest(String cpf) throws Exception {
        if(cpf == null) throw new Exception("CPF não pode ser nulo.");
        if(cpf.length() != 11) throw new Exception("CPF não pode ter mais ou menos que 11 digitos.");

        this.cpf = cpf;
    }

    public String getCPF() { return this.cpf; }

    @Override
    public String toString() { return "CPF: " + this.cpf; }
}