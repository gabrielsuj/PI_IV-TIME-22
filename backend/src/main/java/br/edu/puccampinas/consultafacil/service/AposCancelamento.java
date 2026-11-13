package br.edu.puccampinas.consultafacil.service;

import org.bson.Document;

/**
 * Ponto de extensão chamado depois que uma consulta é cancelada (#27).
 *
 * Quando o método é chamado, o cancelamento já está gravado e a vaga já voltou para GET /vagas.
 * A fila de espera (#32) implementa esta interface para sinalizar os interessados compatíveis
 * (docs/modelagem/filaEspera.md). Até lá, o App usa {@link #NENHUM}.
 *
 * Uma falha aqui não desfaz o cancelamento: o CancelamentoService registra no log e segue.
 */
@FunctionalInterface
public interface AposCancelamento {
    /** @param consultaCancelada consulta já com status CANCELADA (pacienteId, medicoId, especialidadeId, inicio, fim). */
    void vagaLiberada(Document consultaCancelada);

    AposCancelamento NENHUM = consulta -> {};
}
