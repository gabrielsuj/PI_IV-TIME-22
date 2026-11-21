package br.edu.puccampinas.consultafacil.domain.enums;
/**
 * @author Murilo Rigoni (criador)
 * @author Gabriel (modificador)
 */
public enum EventoHistorico {
    CRIACAO, CONFIRMACAO, ALTERACAO, CANCELAMENTO, CONCLUSAO, FALTA,
    /** Gravado pelo SISTEMA logo após um CANCELAMENTO, com o total de interessados avisados (#32). */
    SINALIZACAO_FILA
}
