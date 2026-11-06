package br.edu.puccampinas.consultafacil.unit;

import br.edu.puccampinas.consultafacil.api.dto.PacienteRequest;
import br.edu.puccampinas.consultafacil.domain.exception.ValidacaoException;
import br.edu.puccampinas.consultafacil.service.PacienteValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PacienteValidatorTest {
    private final PacienteValidator validator = new PacienteValidator();

    @Test void normalizaDadosValidos() {
        var p = validator.validar(new PacienteRequest("  Ana Silva  ", "529.982.247-25", " ANA@EXEMPLO.COM "));
        assertEquals(new PacienteRequest("Ana Silva", "52998224725", "ana@exemplo.com"), p);
    }

    @Test void rejeitaRequestNulo() {
        assertThrows(ValidacaoException.class, () -> validator.validar(null));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t\n"})
    void exigeNome(String nome) { invalido(new PacienteRequest(nome, "52998224725", "ana@exemplo.com"), "Nome"); }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t\n"})
    void exigeCpf(String cpf) { invalido(new PacienteRequest("Ana", cpf, "ana@exemplo.com"), "CPF"); }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t\n"})
    void exigeEmail(String email) { invalido(new PacienteRequest("Ana", "52998224725", email), "E-mail"); }

    @ParameterizedTest
    @ValueSource(strings = {"00000000000", "11111111111", "52998224724", "52998224735",
            "123", "529982247250", "abc52998224725", "529-982-247.25"})
    void rejeitaCpfInvalido(String cpf) { invalido(new PacienteRequest("Ana", cpf, "ana@exemplo.com"), "CPF"); }

    @ParameterizedTest
    @ValueSource(strings = {"ana", "ana@", "@exemplo.com", "ana@exemplo", "a b@exemplo.com",
            ".ana@exemplo.com", "ana..silva@exemplo.com", "ana.@exemplo.com", "ana@-exemplo.com"})
    void rejeitaEmailInvalido(String email) { invalido(new PacienteRequest("Ana", "52998224725", email), "E-mail"); }

    @Test void rejeitaEmailLongo() {
        invalido(new PacienteRequest("Ana", "52998224725", "a".repeat(65) + "@exemplo.com"), "E-mail");
    }

    private void invalido(PacienteRequest request, String campo) {
        var erro = assertThrows(ValidacaoException.class, () -> validator.validar(request));
        assertTrue(erro.getMessage().contains(campo));
    }
}
