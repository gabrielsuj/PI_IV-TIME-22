package br.edu.puccampinas.consultafacil.unit;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.AgendaRequest;
import br.edu.puccampinas.consultafacil.api.dto.FaixaDto;
import br.edu.puccampinas.consultafacil.api.dto.JornadaDto;
import br.edu.puccampinas.consultafacil.service.ValidadorAgenda;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ValidadorAgendaTest {

    private static AgendaRequest agenda(int duracao, JornadaDto... jornadas) {
        return new AgendaRequest(duracao, "America/Sao_Paulo", List.of(jornadas));
    }

    /** dia(1, 480, 720, 780, 1020) = segunda, 08h–12h e 13h–17h */
    private static JornadaDto dia(int diaSemana, int... inicioFim) {
        List<FaixaDto> faixas = new ArrayList<>();
        for (int i = 0; i < inicioFim.length; i += 2) faixas.add(new FaixaDto(inicioFim[i], inicioFim[i + 1]));
        return new JornadaDto(diaSemana, faixas);
    }

    private static void deveRecusar(AgendaRequest r) {
        ApiException e = assertThrows(ApiException.class, () -> ValidadorAgenda.validar(r));
        assertEquals(400, e.status());
    }

    @Test
    void deveAceitarAgendaDaAna() {
        assertDoesNotThrow(() -> ValidadorAgenda.validar(agenda(30, dia(1, 480, 720, 780, 1020), dia(3, 480, 720))));
    }

    @Test
    void deveAceitarFaixasEncostadas() {
        assertDoesNotThrow(() -> ValidadorAgenda.validar(agenda(30, dia(1, 480, 720, 720, 780))));
    }

    @Test
    void deveRecusarFaixasSobrepostas() {
        deveRecusar(agenda(30, dia(1, 480, 720, 700, 800)));
    }

    @Test
    void deveRecusarDiaRepetido() {
        deveRecusar(agenda(30, dia(1, 480, 720), dia(1, 800, 900)));
    }

    @Test
    void deveRecusarFaixaComFimAntesDoInicio() {
        deveRecusar(agenda(30, dia(2, 720, 480)));
    }

    @Test
    void deveRecusarDuracaoQueNaoCabeNaFaixa() {
        deveRecusar(agenda(60, dia(2, 480, 510)));
    }

    @Test
    void deveRecusarDuracaoForaDoLimite() {
        deveRecusar(agenda(4, dia(2, 480, 720)));
        deveRecusar(agenda(241, dia(2, 0, 1440)));
    }

    @Test
    void deveRecusarDiaSemanaForaDoPadraoIso() {
        deveRecusar(agenda(30, dia(0, 480, 720)));
        deveRecusar(agenda(30, dia(8, 480, 720)));
    }

    @Test
    void deveRecusarDiaSemFaixas() {
        deveRecusar(agenda(30, new JornadaDto(1, List.of())));
    }

    @Test
    void deveRecusarFusoInvalido() {
        deveRecusar(new AgendaRequest(30, "Brasil/Campinas", List.of(dia(1, 480, 720))));
    }
}
