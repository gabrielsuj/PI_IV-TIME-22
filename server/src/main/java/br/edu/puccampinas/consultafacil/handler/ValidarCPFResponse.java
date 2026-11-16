package br.edu.puccampinas.consultafacil.handler;

import br.edu.puccampinas.consultafacil.protocol.Comunicado;

public class ValidarCPFResponse extends Comunicado {
    private final String cpf;
    private final boolean valido;
    private final String mensagem;

    public ValidarCPFResponse(String cpf, boolean valido, String mensagem) throws Exception {
        if(mensagem == null) throw new Exception("Mensagem não pode ser nula");

        this.cpf = cpf;
        this.valido = valido;
        this.mensagem = mensagem;
    }

    public String getCPF() { return this.cpf; }

    public boolean isValido() { return this.valido; }

    public String getMensagem() { return this.mensagem; }

    @Override
    public String toString() { return "CPF: " + cpf + "\nValido: " + valido; }
}
