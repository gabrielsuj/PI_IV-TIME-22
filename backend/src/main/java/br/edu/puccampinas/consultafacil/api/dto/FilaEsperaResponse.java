package br.edu.puccampinas.consultafacil.api.dto;

/**
 * Inscrição na fila devolvida pela API (#31). Ver docs/api/fila-espera.md.
 * dataInicio/dataFim: AAAA-MM-DD ou null. Datas com hora: ISO 8601 UTC.
 */
public record FilaEsperaResponse(String id, String pacienteId, String pacienteNome,
                                 String especialidadeId, String especialidadeNome,
                                 String medicoId, String medicoNome, String turnoPreferido,
                                 String dataInicio, String dataFim, String status,
                                 String validoAte, String criadoEm, Sinalizacao ultimaSinalizacao) {

    /** Vaga liberada que gerou o aviso (preenchida só em SINALIZADA). */
    public record Sinalizacao(String consultaOrigemId, String medicoId, String medicoNome,
                              String dataHora, String sinalizadaEm) {}
}
