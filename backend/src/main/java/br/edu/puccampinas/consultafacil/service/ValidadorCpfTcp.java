
package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.client.Cliente;
import br.edu.puccampinas.consultafacil.handler.ValidarCPFRequest;
import br.edu.puccampinas.consultafacil.handler.ValidarCPFResponse;
import br.edu.puccampinas.consultafacil.domain.exception.ValidacaoException;
import br.edu.puccampinas.consultafacil.domain.exception.ServidorCpfIndisponivelException;
/*@author Murilo Rigoni (criador) */
public class ValidadorCpfTcp implements ValidadorCpf {

    private final String host;
    private final int porta;

    public ValidadorCpfTcp(String host, int porta) {
        this.host = host;
        this.porta = porta;
    }

    @Override
    public void validar(String cpf) {
        Cliente cliente = null;
        try {
            cliente = new Cliente(host, porta);

            ValidarCPFRequest request = new ValidarCPFRequest(cpf);

            ValidarCPFResponse response = cliente.validarCPF(request);

            if (!response.isValido()) {
                throw new ValidacaoException("CPF inválido.");
            }

        } catch (ValidacaoException e) {
            throw e;
        }  catch (Exception e) {
    throw new ServidorCpfIndisponivelException(e);
} finally {
    if (cliente != null) cliente.close();
}
    }
}
