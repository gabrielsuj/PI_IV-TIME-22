package br.edu.puccampinas.consultafacil.handler;

import org.junit.Test;
import static org.junit.Assert.*;

public class ValidarCPFHandlerTest {
    @Test public void pedidoNuloRetornaRespostaInvalida() throws Exception {
        ValidarCPFResponse resposta = ValidarCPFHandler.ValidateCPF(null);
        assertFalse(resposta.isValido());
        assertNull(resposta.getCPF());
        assertEquals("CPF não foi fornecido.", resposta.getMensagem());
    }

    @Test public void preservaValidacaoDosDigitosEFormato() throws Exception {
        assertTrue(ValidarCPFHandler.ValidateCPF(new ValidarCPFRequest("52998224725")).isValido());
        for (String cpf : new String[]{"11111111111", "52998224724", "abcdefghijk"}) {
            assertFalse(cpf, ValidarCPFHandler.ValidateCPF(new ValidarCPFRequest(cpf)).isValido());
        }
    }
}
