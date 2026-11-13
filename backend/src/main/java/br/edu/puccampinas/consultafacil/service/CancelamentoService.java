package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.ConsultaResponse;
import br.edu.puccampinas.consultafacil.domain.enums.StatusConsulta;
import br.edu.puccampinas.consultafacil.repository.PendenciasConsultaRepository;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;

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

    private final PendenciasConsultaRepository consultas;
    private final AcessoConsulta acesso;
    private final RegistroConsultaService registro;
    private final AposCancelamento aposCancelamento;
    private final Clock relogio;

    public CancelamentoService(PendenciasConsultaRepository consultas, AcessoConsulta acesso,
                               RegistroConsultaService registro, AposCancelamento aposCancelamento, Clock relogio) {
        this.consultas = consultas;
        this.acesso = acesso;
        this.registro = registro;
        this.aposCancelamento = aposCancelamento;
        this.relogio = relogio;
    }

    /**
     * Regra de prazo e status num lugar só: ativa (AGENDADA/CONFIRMADA) e ainda não começou.
     * O detalhe da consulta (#30) usa para decidir se mostra o botão "Cancelar".
     */
    public static boolean podeCancelar(Document consulta, Instant agora) {
        return StatusConsulta.ATIVOS.contains(consulta.getString("status"))
                && consulta.getDate("inicio").toInstant().isAfter(agora);
    }

    public ConsultaResponse cancelar(ObjectId consultaId, String motivo, UsuarioLogado ator) {
        Document consulta = consultas.buscar(consultaId)
                .orElseThrow(() -> ApiException.notFound("Consulta não encontrada"));
        acesso.verificar(consulta, ator);
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
