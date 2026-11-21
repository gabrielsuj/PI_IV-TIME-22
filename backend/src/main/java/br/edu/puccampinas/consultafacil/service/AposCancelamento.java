package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import org.bson.Document;

/**
 * Ponto de extensão chamado depois que uma consulta é cancelada (#27).
 *
 * Quando o método é chamado, o cancelamento já está gravado e a vaga já voltou para GET /vagas.
 * A fila de espera (#32) é avisada pelo RegistroConsultaService; este gancho é usado pelas
 * notificações (#34): aviso de cancelamento às outras partes e descarte dos lembretes pendentes.
 *
 * Uma falha aqui não desfaz o cancelamento: o CancelamentoService registra no log e segue.
 */
@FunctionalInterface
public interface AposCancelamento {
    /**
     * @param consultaCancelada consulta já com status CANCELADA (pacienteId, medicoId, especialidadeId, inicio, fim)
     * @param quemCancelou usuário que pediu o cancelamento (quem cancela não é notificado do próprio cancelamento)
     */
    void consultaCancelada(Document consultaCancelada, UsuarioLogado quemCancelou);

    AposCancelamento NENHUM = (consulta, ator) -> {};
}
