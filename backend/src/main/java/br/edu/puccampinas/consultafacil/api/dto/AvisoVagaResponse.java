package br.edu.puccampinas.consultafacil.api.dto;

/** Aviso de vaga do paciente. Datas em ISO 8601 UTC. */
public record AvisoVagaResponse(String id, String medicoNome, String inicio, String fim, String validoAte,
                                Status status, String consultaAgendadaId) {
    public enum Status { DISPONIVEL, EXPIRADO, AGENDADO }
}
