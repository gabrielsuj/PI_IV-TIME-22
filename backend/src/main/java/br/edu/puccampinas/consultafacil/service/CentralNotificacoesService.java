/*
Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.repository.ConsultaRepository;
import br.edu.puccampinas.consultafacil.repository.NotificacaoRepository;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Clock;
import java.util.Date;
import java.util.List;

public final class CentralNotificacoesService {
    /**
     * {@code consultaCancelada}: a consulta citada foi cancelada depois deste aviso (#38). Evita que um
     * lembrete ou uma confirmação de agendamento já entregues pareçam válidos na central.
     */
    public record Item(String id, String titulo, String mensagem, String medicoNome, String dataHora,
                       boolean lida, boolean presencaConfirmada, boolean podeConfirmar, boolean consultaCancelada) {}

    private final NotificacaoRepository notificacoes;
    private final ConsultaRepository consultas;
    private final AcessoConsulta.VinculoDoUsuario pacientes, medicos;
    private final StatusConsultaService status;
    private final Clock relogio;

    public CentralNotificacoesService(NotificacaoRepository notificacoes, ConsultaRepository consultas,
            AcessoConsulta.VinculoDoUsuario pacientes, AcessoConsulta.VinculoDoUsuario medicos,
            StatusConsultaService status, Clock relogio) {
        this.notificacoes = notificacoes;
        this.consultas = consultas;
        this.pacientes = pacientes;
        this.medicos = medicos;
        this.status = status;
        this.relogio = relogio;
    }

    private ObjectId destinatario(UsuarioLogado ator) {
        var vinculo = switch (ator.perfil()) {
            case PACIENTE -> pacientes.buscar(ator.id());
            case MEDICO -> medicos.buscar(ator.id());
            default -> throw ApiException.forbidden("Perfil sem central de notificações");
        };
        return vinculo.orElseThrow(() -> ApiException.forbidden("O login não está vinculado a um cadastro"));
    }

    public List<Item> listar(UsuarioLogado ator) {
        return notificacoes.entregues(ator.perfil(), destinatario(ator)).stream().map(n -> {
            Document dados = n.get("dadosConsulta", Document.class);
            String tipo = n.getString("tipo");
            boolean confirmada = n.getDate("confirmadaEm") != null;
            boolean podeConfirmar = false;
            // A consulta só conta se ainda for o mesmo horário do aviso (uma remarcação gera outro aviso).
            ObjectId consultaId = n.getObjectId("consultaId");
            Document consulta = consultaId == null ? null : consultas.buscar(consultaId)
                    .filter(c -> c.getDate("inicio").equals(dados.getDate("dataHora"))).orElse(null);
            boolean consultaCancelada = consulta != null && !"CANCELAMENTO_CONSULTA".equals(tipo)
                    && "CANCELADA".equals(consulta.getString("status"));
            if (!confirmada && "LEMBRETE_CONSULTA".equals(tipo) && consulta != null
                    && consulta.getObjectId("pacienteId").equals(n.getObjectId("destinatarioId"))) {
                confirmada = "CONFIRMADA".equals(consulta.getString("status"));
                podeConfirmar = "AGENDADA".equals(consulta.getString("status"))
                        && consulta.getDate("inicio").toInstant().isAfter(relogio.instant());
            }
            return new Item(n.getObjectId("_id").toHexString(), n.getString("titulo"), n.getString("mensagem"),
                    dados.getString("medicoNome"), dados.getDate("dataHora").toInstant().toString(),
                    n.getDate("lidaEm") != null, confirmada, podeConfirmar, consultaCancelada);
        }).toList();
    }

    public void marcarLida(ObjectId id, UsuarioLogado ator) {
        notificacoes.registrarLeitura(id, ator.perfil(), destinatario(ator), Date.from(relogio.instant()), false);
    }

    public void confirmar(ObjectId id, UsuarioLogado ator) {
        if (ator.perfil() != Perfil.PACIENTE) throw ApiException.forbidden("Somente pacientes confirmam presença");
        ObjectId paciente = destinatario(ator);
        var n = notificacoes.entregue(id, ator.perfil(), paciente)
                .orElseThrow(() -> ApiException.notFound("Notificação não encontrada"));
        if (!"LEMBRETE_CONSULTA".equals(n.getString("tipo"))) throw ApiException.conflict("Selecione um lembrete de consulta");
        if (n.getDate("confirmadaEm") != null) return;
        status.confirmarPeloLembrete(n.getObjectId("consultaId"), n.get("dadosConsulta", Document.class).getDate("dataHora"), ator);
        notificacoes.registrarLeitura(id, ator.perfil(), paciente, Date.from(relogio.instant()), true);
    }
}
