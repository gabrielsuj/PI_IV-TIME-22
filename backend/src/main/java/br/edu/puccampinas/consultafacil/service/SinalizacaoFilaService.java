package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.domain.enums.EventoHistorico;
import br.edu.puccampinas.consultafacil.domain.enums.StatusConsulta;
import br.edu.puccampinas.consultafacil.repository.ConsultaRepository;
import br.edu.puccampinas.consultafacil.repository.FilaEsperaRepository;
import br.edu.puccampinas.consultafacil.repository.NomesRepository;
import br.edu.puccampinas.consultafacil.repository.NotificacaoRepository;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sinalização da fila de espera quando uma consulta é cancelada (#32).
 *
 * Para cada interesse compatível com a vaga liberada, em ordem de chegada: cria uma notificação
 * VAGA_LIBERADA (status AGENDADA, para quem exibe/envia os avisos) e passa o interesse a SINALIZADA,
 * com ultimaSinalizacao. Ao final grava no histórico da consulta o evento SINALIZACAO_FILA
 * (ator SISTEMA, sequência seguinte à do CANCELAMENTO) com o total de sinalizados, mesmo que seja 0.
 *
 * A vaga não é reservada: todos os compatíveis são avisados e quem agendar primeiro fica com ela
 * (os índices únicos de consultas garantem uma única reserva).
 */
public final class SinalizacaoFilaService {
    private static final Logger LOG = LoggerFactory.getLogger(SinalizacaoFilaService.class);
    static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm").withZone(FUSO);

    private final FilaEsperaRepository fila;
    private final NotificacaoRepository notificacoes;
    private final ConsultaRepository consultas;
    private final NomesRepository nomes;
    private final HistoricoProcessor historico;
    private final Clock relogio;

    public SinalizacaoFilaService(FilaEsperaRepository fila, NotificacaoRepository notificacoes, ConsultaRepository consultas,
                                  NomesRepository nomes, HistoricoProcessor historico, Clock relogio) {
        this.fila = fila;
        this.notificacoes = notificacoes;
        this.consultas = consultas;
        this.nomes = nomes;
        this.historico = historico;
        this.relogio = relogio;
    }

    /** Chamado logo após o CANCELAMENTO ser gravado. Retorna quantos interessados foram sinalizados. */
    public int sinalizar(ObjectId consultaId) {
        Document consulta = consultas.buscar(consultaId).orElse(null);
        if (consulta == null || !StatusConsulta.CANCELADA.name().equals(consulta.getString("status"))) return 0;
        Date agora = Date.from(relogio.instant());
        int total = vagaFutura(consulta, agora) ? avisarInteressados(consulta, agora) : 0;
        registrarHistorico(consulta, total, agora);
        return total;
    }

    private static boolean vagaFutura(Document consulta, Date agora) {
        return consulta.getDate("inicio").after(agora);
    }

    private int avisarInteressados(Document consulta, Date agora) {
        ObjectId consultaId = consulta.getObjectId("_id");
        ObjectId medicoId = consulta.getObjectId("medicoId");
        Date inicio = consulta.getDate("inicio");
        Date fim = consulta.getDate("fim");
        String medicoNome = nomes.medicos(List.of(medicoId)).getOrDefault(medicoId, "o médico");

        fila.revisar(agora); // sinalizações de vagas que já passaram voltam a concorrer
        // Quem cancelou não é avisado da própria vaga; e cada paciente recebe no máximo um aviso por vaga.
        Set<ObjectId> pacientesConsiderados = new HashSet<>();
        pacientesConsiderados.add(consulta.getObjectId("pacienteId"));
        int total = 0;
        for (Document interesse : fila.candidatos(consulta.getObjectId("especialidadeId"), medicoId, inicio, agora)) {
            ObjectId pacienteId = interesse.getObjectId("pacienteId");
            if (!turnoCompativel(interesse.getString("turnoPreferido"), inicio.toInstant())) continue;
            if (!pacientesConsiderados.add(pacienteId)) continue;
            if (consultas.pacienteTemAtivaSobreposta(pacienteId, inicio, fim)) continue;

            ObjectId notificacaoId = new ObjectId();
            Document aviso = notificacao(notificacaoId, pacienteId, consultaId, interesse.getObjectId("_id"),
                    inicio, medicoNome, agora);
            if (!notificacoes.inserirSeNova(aviso)) continue; // já avisado num processamento anterior
            Document ultima = new Document("consultaOrigemId", consultaId).append("medicoId", medicoId)
                    .append("dataHora", inicio).append("sinalizadaEm", agora).append("notificacaoId", notificacaoId);
            if (fila.marcarSinalizada(interesse.getObjectId("_id"), ultima, agora)) total++;
            else notificacoes.descartar(notificacaoId); // o interesse mudou nesse meio-tempo
        }
        return total;
    }

    /**
     * Chamado logo após uma consulta ser criada. Se ela ocupa uma vaga sinalizada:
     * o interessado que agendou passa a ATENDIDA; os demais avisados daquela vaga voltam a AGUARDANDO
     * (mantendo a posição) e o aviso deles, se ainda não foi enviado, é descartado.
     * Retorna quantas inscrições mudaram.
     */
    public int consultaAgendada(Document consulta) {
        Date agora = Date.from(relogio.instant());
        ObjectId pacienteId = consulta.getObjectId("pacienteId");
        int alteradas = 0;
        for (Document inscricao : fila.sinalizadasParaVaga(consulta.getObjectId("medicoId"), consulta.getDate("inicio"))) {
            ObjectId id = inscricao.getObjectId("_id");
            if (pacienteId.equals(inscricao.getObjectId("pacienteId"))) {
                if (fila.marcarAtendida(id, consulta.getObjectId("_id"), agora)) alteradas++;
            } else if (fila.voltarParaAguardando(id, agora)) {
                notificacoes.descartar(inscricao.get("ultimaSinalizacao", Document.class).getObjectId("notificacaoId"));
                alteradas++;
            }
        }
        return alteradas;
    }

    /**
     * O horário de início da vaga atende ao turno preferido do interessado?
     * MANHA, TARDE ou QUALQUER (docs/modelagem/filaEspera.md).
     */
    public static boolean turnoCompativel(String turnoPreferido, Instant inicioVaga) {
        // A hora é avaliada em São Paulo: 12:00Z ainda é 09:00 local (manhã).
        int hora = inicioVaga.atZone(FUSO).getHour();
        return switch (turnoPreferido) {
            case "MANHA" -> hora < 12;
            case "TARDE" -> hora >= 12;
            default -> true; // QUALQUER
        };
    }

    private static Document notificacao(ObjectId id, ObjectId pacienteId, ObjectId consultaId, ObjectId filaEsperaId,
                                        Date inicio, String medicoNome, Date agora) {
        return new Document("_id", id)
                .append("destinatarioPerfil", "PACIENTE").append("destinatarioId", pacienteId)
                .append("tipo", "VAGA_LIBERADA").append("canal", "SISTEMA").append("simulada", true)
                .append("consultaId", consultaId).append("filaEsperaId", filaEsperaId)
                .append("statusConsulta", StatusConsulta.CANCELADA.name()).append("antecedenciaHoras", null)
                .append("titulo", "Vaga disponível")
                // Sem CPF, motivo ou dado de saúde (regra da coleção notificacoes).
                .append("mensagem", "Abriu um horário com " + medicoNome + " em " + DATA_HORA.format(inicio.toInstant())
                        + ". Acesse o ConsultaFácil para agendar; a vaga fica com quem agendar primeiro.")
                .append("dadosConsulta", new Document("dataHora", inicio).append("medicoNome", medicoNome))
                .append("status", "AGENDADA").append("agendadaPara", agora).append("enviadaEm", null)
                .append("tentativas", 0).append("erro", null).append("criadoEm", agora);
    }

    /** Evento SINALIZACAO_FILA pela mesma caixa de pendências usada pelo RegistroConsultaService. */
    private void registrarHistorico(Document consulta, int total, Date agora) {
        ObjectId id = consulta.getObjectId("_id");
        int versao = consulta.getInteger("versao", 0);
        int sequencia = (versao == 0 ? historico.ultimaSequencia(id) : versao) + 1;
        Document evento = new Document("_id", new ObjectId()).append("consultaId", id)
                .append("sequencia", sequencia).append("evento", EventoHistorico.SINALIZACAO_FILA.name())
                .append("statusAnterior", StatusConsulta.CANCELADA.name())
                .append("statusNovo", StatusConsulta.CANCELADA.name())
                .append("pacienteId", consulta.getObjectId("pacienteId"))
                .append("medicoId", consulta.getObjectId("medicoId"))
                .append("especialidadeId", consulta.getObjectId("especialidadeId"))
                .append("dataHoraConsulta", consulta.getDate("inicio")).append("fimConsulta", consulta.getDate("fim"))
                .append("ator", new Document("perfil", "SISTEMA").append("id", null))
                .append("totalSinalizados", total)
                .append("ocorridoEm", agora);
        if (consultas.atualizarComEvento(id, versao, new Document(), evento)) historico.processarConsulta(id);
        else LOG.warn("SINALIZACAO_FILA não registrada para a consulta {} (versão mudou)", id);
    }
}
