/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.ConsultaResponse;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.domain.enums.StatusConsulta;
import br.edu.puccampinas.consultafacil.repository.PendenciasConsultaRepository;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Clock;

/**
 * Transições da #28, sempre com propriedade verificada e gravação auditada.
 * Todos os perfis podem confirmar uma AGENDADA a que tenham acesso, sem prazo adicional.
 * Médico/secretaria podem concluir uma CONFIRMADA ou registrar falta em AGENDADA/CONFIRMADA,
 * somente a partir do fim previsto. Estados finais não aceitam novas transições.
 * Cancelamento permanece no CancelamentoService, com seu prazo e ganchos próprios.
 */
public final class StatusConsultaService {
    private final PendenciasConsultaRepository consultas;
    private final AcessoConsulta acesso;
    private final RegistroConsultaService registro;
    private final Clock relogio;

    public StatusConsultaService(PendenciasConsultaRepository consultas, AcessoConsulta acesso,
                                 RegistroConsultaService registro, Clock relogio) {
        this.consultas = consultas;
        this.acesso = acesso;
        this.registro = registro;
        this.relogio = relogio;
    }

    public ConsultaResponse alterar(ObjectId consultaId, StatusConsulta destino, UsuarioLogado ator) {
        return alterar(consultaId, destino, ator, null);
    }

    void confirmarPeloLembrete(ObjectId id, java.util.Date horario, UsuarioLogado ator) {
        alterar(id, StatusConsulta.CONFIRMADA, ator, horario);
    }

    // Restringe este fluxo às transições de comparecimento; o cancelamento mantém suas
    // próprias regras e notificações.
    private ConsultaResponse alterar(ObjectId consultaId, StatusConsulta destino, UsuarioLogado ator, java.util.Date horarioLembrete) {
        if (ator == null || ator.perfil() == null || ator.id() == null || !ObjectId.isValid(ator.id()))
            throw ApiException.unauthorized("Usuário autenticado é obrigatório para alterar o status");
        if (consultaId == null) throw ApiException.badRequest("ID da consulta é obrigatório");
        if (destino != StatusConsulta.CONFIRMADA && destino != StatusConsulta.CONCLUIDA && destino != StatusConsulta.FALTA)
            throw ApiException.badRequest("Informe CONFIRMADA, CONCLUIDA ou FALTA. Cancelamento usa a operação própria.");

        Document consulta = consultas.buscar(consultaId)
                .orElseThrow(() -> ApiException.notFound("Consulta não encontrada"));
        acesso.verificar(consulta, ator);
        if (ator.perfil() == Perfil.PACIENTE && destino != StatusConsulta.CONFIRMADA)
            throw ApiException.forbidden("Somente médico ou secretaria podem concluir a consulta ou registrar falta");

        String atual = consulta.getString("status");
        if (horarioLembrete != null) {
            if (!horarioLembrete.equals(consulta.getDate("inicio")) || !horarioLembrete.toInstant().isAfter(relogio.instant()))
                throw ApiException.conflict("O horário deste lembrete não está mais disponível para confirmação");
            // Uma repetição após a gravação pode completar a leitura sem duplicar o evento no histórico.
            if (StatusConsulta.CONFIRMADA.name().equals(atual)) return ConsultaResponse.de(consulta);
        }
        if (!StatusConsulta.ATIVOS.contains(atual)
                || destino == StatusConsulta.CONFIRMADA && !StatusConsulta.AGENDADA.name().equals(atual)
                || destino == StatusConsulta.CONCLUIDA && !StatusConsulta.CONFIRMADA.name().equals(atual))
            throw ApiException.conflict("Transição de status inválida para esta consulta");
        if (destino != StatusConsulta.CONFIRMADA && consulta.getDate("fim").toInstant().isAfter(relogio.instant()))
            throw ApiException.conflict("A conclusão ou falta só pode ser registrada após o fim previsto da consulta");

        Document alterada = registro.alterar(consultaId, new Document("status", destino.name()), ator,
                consulta.getInteger("versao", 0));
        return ConsultaResponse.de(alterada);
    }
}
