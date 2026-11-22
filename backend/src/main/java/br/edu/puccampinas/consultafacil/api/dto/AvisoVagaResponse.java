/*
Autoria: Murilo Moraes (modificador)
RA: 25000073

Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

package br.edu.puccampinas.consultafacil.api.dto;

/**
 * Aviso de vaga do paciente. Datas em ISO 8601 UTC.
 *
 * <p>{@code motivo} só é preenchido quando {@code status = EXPIRADO} e explica por que a vaga
 * deixou de valer (#58). {@code medicoId} e {@code especialidadeId} permitem que a tela leve o
 * paciente de volta à busca de vagas já com os filtros do aviso.
 */
public record AvisoVagaResponse(String id, String medicoNome, String inicio, String fim, String validoAte,
                                Status status, String consultaAgendadaId, Motivo motivo,
                                String medicoId, String especialidadeId) {
    public enum Status { DISPONIVEL, EXPIRADO, AGENDADO }

    /** Por que um aviso {@code EXPIRADO} deixou de valer. */
    public enum Motivo {
        /** Outra consulta ativa já ocupa o horário oferecido. */
        VAGA_OCUPADA,
        /** Chegou o horário da vaga ou terminou a validade da inscrição na fila. */
        PRAZO_ENCERRADO,
        /** A agenda mudou, a inscrição deixou de corresponder ao aviso ou o aviso foi descartado. */
        OPORTUNIDADE_DESATUALIZADA
    }
}
