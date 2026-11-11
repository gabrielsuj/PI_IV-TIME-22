package br.edu.puccampinas.consultafacil.handler;

import br.edu.puccampinas.consultafacil.protocol.Comunicado;

public class ValidarCPFRequest extends Comunicado {
    private final String cpf;

    public ValidarCPFRequest(String cpf) throws Exception {
        if(cpf == null) throw new Exception("CPF não pode ser nulo.");

        this.cpf = cpf;
    }

    public String getCPF() { return this.cpf; }

    @Override
    public String toString() { return "CPF: " + this.cpf; }
}