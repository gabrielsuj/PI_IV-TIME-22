package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.ConsultaResponse;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.domain.enums.StatusConsulta;
import br.edu.puccampinas.consultafacil.repository.PendenciasConsultaRepository;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Optional;

/**
 * Cancelamento de consulta por paciente, médico ou secretaria (#27).
 *
 * Ordem das verificações: a consulta existe (404) → quem chama tem acesso a ela (403) →
 * ainda está ativa (409) → ainda não começou (409). Depois grava pelo RegistroConsultaService,
 * que muda o status, registra o evento CANCELAMENTO no histórico e protege contra alteração simultânea.
 *
 * A vaga é liberada pela própria mudança de status: os índices únicos de `consultas` e o cálculo de
 * disponibilidade só consideram consultas AGENDADA/CONFIRMADA (docs/modelagem/consultas.md).
 */
public class CancelamentoService {
    private static final Logger LOG = LoggerFactory.getLogger(CancelamentoService.class);

    public static final String JA_COMECOU =
            "Esta consulta já começou e não pode mais ser cancelada. Registre a conclusão ou a falta.";

    /** Busca o cadastro (paciente ou médico) ligado a um login. */
    @FunctionalInterface
    public interface VinculoDoUsuario {
        Optional<ObjectId> buscar(String usuarioId);
    }

    private final PendenciasConsultaRepository consultas;
    private final VinculoDoUsuario pacienteDoUsuario;
    private final VinculoDoUsuario medicoDoUsuario;
    private final RegistroConsultaService registro;
    private final AposCancelamento aposCancelamento;
    private final Clock relogio;

    public CancelamentoService(PendenciasConsultaRepository consultas, VinculoDoUsuario pacienteDoUsuario,
                               VinculoDoUsuario medicoDoUsuario, RegistroConsultaService registro,
                               AposCancelamento aposCancelamento, Clock relogio) {
        this.consultas = consultas;
        this.pacienteDoUsuario = pacienteDoUsuario;
        this.medicoDoUsuario = medicoDoUsuario;
        this.registro = registro;
        this.aposCancelamento = aposCancelamento;
        this.relogio = relogio;
    }

    public ConsultaResponse cancelar(ObjectId consultaId, String motivo, UsuarioLogado ator) {
        if (ator == null || ator.perfil() == null) throw ApiException.unauthorized("Faça login para cancelar a consulta");

        Document consulta = consultas.buscar(consultaId)
                .orElseThrow(() -> ApiException.notFound("Consulta não encontrada"));
        verificarAcesso(consulta, ator);
        verificarStatus(consulta);
        // Regra escolhida para o MVP: só cancela o que ainda não começou, para qualquer perfil.
        // Consulta passada não libera vaga (GET /vagas já esconde horários passados); o certo é FALTA ou CONCLUIDA (#28).
        if (!consulta.getDate("inicio").toInstant().isAfter(relogio.instant()))
            throw ApiException.conflict(JA_COMECOU);

        Document campos = new Document("status", StatusConsulta.CANCELADA.name());
        String motivoLimpo = limpar(motivo);
        if (motivoLimpo != null) campos.append("motivoCancelamento", motivoLimpo);

        Document cancelada = registro.alterar(consultaId, campos, ator);
        avisarFila(cancelada);
        return ConsultaResponse.de(cancelada);
    }

    /** Paciente: só as próprias consultas. Médico: só as consultas em que ele atende. Secretaria: todas. */
    private void verificarAcesso(Document consulta, UsuarioLogado ator) {
        if (ator.perfil() == Perfil.SECRETARIA_ADMIN) return;

        if (ator.perfil() == Perfil.PACIENTE) {
            ObjectId proprio = pacienteDoUsuario.buscar(ator.id()).orElseThrow(() ->
                    ApiException.forbidden("Seu login não está ligado a um cadastro de paciente. Procure a secretaria."));
            if (!proprio.equals(consulta.getObjectId("pacienteId")))
                throw ApiException.forbidden("O paciente só pode cancelar as próprias consultas");
            return;
        }

        if (ator.perfil() == Perfil.MEDICO) {
            ObjectId proprio = medicoDoUsuario.buscar(ator.id()).orElseThrow(() ->
                    ApiException.forbidden("Seu login não está ligado a um cadastro de médico. Procure a secretaria."));
            if (!proprio.equals(consulta.getObjectId("medicoId")))
                throw ApiException.forbidden("O médico só pode cancelar as próprias consultas");
            return;
        }

        throw ApiException.forbidden("Seu perfil não pode cancelar consultas");
    }

    private static void verificarStatus(Document consulta) {
        String status = consulta.getString("status");
        if (StatusConsulta.ATIVOS.contains(status)) return;
        throw ApiException.conflict(switch (status) {
            case "CANCELADA" -> "Esta consulta já foi cancelada.";
            case "CONCLUIDA" -> "Esta consulta já foi concluída e não pode ser cancelada.";
            case "FALTA" -> "Esta consulta foi registrada como falta e não pode ser cancelada.";
            default -> "Esta consulta não pode ser cancelada no status atual.";
        });
    }

    /** Espaços nas pontas são removidos; motivo em branco é tratado como ausente. O limite de 255 fica no RegistroConsultaService. */
    private static String limpar(String motivo) {
        if (motivo == null) return null;
        String texto = motivo.strip();
        return texto.isEmpty() ? null : texto;
    }

    /** O cancelamento já está gravado: uma falha na fila (#32) não pode virar erro para quem cancelou. */
    private void avisarFila(Document cancelada) {
        try {
            aposCancelamento.vagaLiberada(cancelada);
        } catch (RuntimeException e) {
            LOG.warn("Consulta {} cancelada, mas o aviso à fila falhou ({})",
                    cancelada.getObjectId("_id"), e.getClass().getSimpleName());
        }
    }
}
