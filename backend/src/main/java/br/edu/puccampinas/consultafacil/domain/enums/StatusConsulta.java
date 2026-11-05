package br.edu.puccampinas.consultafacil.domain.enums;

import java.util.List;

public enum StatusConsulta {
    AGENDADA, CONFIRMADA, CANCELADA, CONCLUIDA, FALTA;

    /** Status que ocupam a vaga */
    public static final List<String> ATIVOS = List.of(AGENDADA.name(), CONFIRMADA.name());
}