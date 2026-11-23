/*
Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.domain.enums.EventoHistorico;
import br.edu.puccampinas.consultafacil.domain.enums.StatusConsulta;
import br.edu.puccampinas.consultafacil.repository.ConsultaRepository;
import br.edu.puccampinas.consultafacil.repository.FilaEsperaRepository;
import br.edu.puccampinas.consultafacil.repository.NomesRepository;
import br.edu.puccampinas.consultafacil.repository.NotificacaoRepository;
import br.edu.puccampinas.consultafacil.repository.TravaAgenda;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
    private final DisponibilidadeService disponibilidade;
    private final NomesRepository nomes;
    private final HistoricoProcessor historico;
    private final Clock relogio;
    private ObjectId cursor;

    public SinalizacaoFilaService(FilaEsperaRepository fila, NotificacaoRepository notificacoes, ConsultaRepository consultas,
                                  NomesRepository nomes, HistoricoProcessor historico, Clock relogio,
                                  DisponibilidadeService disponibilidade) {
        this.fila = fila;
        this.notificacoes = notificacoes;
        this.consultas = consultas;
        this.disponibilidade = disponibilidade;
        this.nomes = nomes;
        this.historico = historico;
        this.relogio = relogio;
    }

    /** A API tem uma instância; serializa o callback imediato e a retomada periódica. */
    public synchronized int sinalizar(ObjectId consultaId) {
        Document consulta = consultas.buscar(consultaId).orElse(null);
        if (consulta == null || consulta.get("sinalizacaoFila") == null) return 0;
        int total = 0;
        if ("PENDENTE".equals(consulta.getString("sinalizacaoFila"))) {
            Date agora = Date.from(relogio.instant());
            Map<ObjectId, Document> avisos = new HashMap<>();
            for (Document aviso : notificacoes.avisosDaConsulta(consultaId))
                avisos.put(aviso.getObjectId("destinatarioId"), aviso);
            if (vagaFutura(consulta, agora) && !reconciliarVaga(consulta))
                avisarInteressados(consulta, agora, avisos);

            var sinalizadas = new HashSet<>(fila.inscricoesSinalizadas(consultaId));
            for (Document aviso : avisos.values()) {
                if (!sinalizadas.contains(aviso.getObjectId("filaEsperaId")))
                    notificacoes.descartar(aviso.getObjectId("_id"));
            }
            total = sinalizadas.size();
            if (total > 0) reconciliarVaga(consulta);
            if (!registrarHistorico(consulta, total, agora)) return total;
        }
        fila.limparComprovantes(consultaId);
        consultas.concluirLimpezaFila(consultaId);
        return total;
    }

    public void recuperarPendentes() {
        try {
            var ids = consultas.sinalizacoesPendentes(cursor, 50);
            for (ObjectId id : ids) {
                try { sinalizar(id); }
                catch (RuntimeException e) { LOG.warn("Sinalização pendente para consulta {}", id, e); }
            }
            cursor = ids.size() == 50 ? ids.getLast() : null;
        } catch (RuntimeException e) {
            LOG.warn("Recuperação da sinalização adiada", e);
        }
    }

    /** Revalida os avisos depois de gravar uma alteração na agenda. */
    public void revisarAgenda(ObjectId medicoId) {
        try {
            for (ObjectId origem : fila.origensSinalizadasDoMedico(medicoId))
                consultas.buscar(origem).ifPresent(this::reconciliarVaga);
        } catch (RuntimeException e) {
            // A agenda já foi gravada. A leitura ou tentativa de uso do aviso volta a validá-lo.
            LOG.warn("Revisão dos avisos adiada para o médico {}", medicoId, e);
        }
    }

    /** Retorna true se o intervalo oferecido deixou de poder ser agendado. */
    boolean reconciliarVaga(Document origem) {
        ObjectId medicoId = origem.getObjectId("medicoId");
        Date inicio = origem.getDate("inicio");
        synchronized (TravaAgenda.porMedico(medicoId)) {
            var ocupante = consultas.ativaNaVaga(medicoId, inicio);
            if (ocupante.isPresent()) {
                consultaAgendada(ocupante.get());
                return true;
            }

            try {
                disponibilidade.exigirVagaLivre(medicoId, inicio.toInstant(), origem.getDate("fim").toInstant());
                return false;
            } catch (ApiException e) {
                if (e.status() != 409) throw e;
            }

            Date agora = Date.from(relogio.instant());
            ObjectId origemId = origem.getObjectId("_id");
            for (Document inscricao : fila.sinalizadasParaVaga(medicoId, inicio)) {
                var ultima = inscricao.get("ultimaSinalizacao", Document.class);
                if (origemId.equals(ultima.getObjectId("consultaOrigemId")))
                    devolverParaFila(inscricao.getObjectId("_id"), ultima.getObjectId("notificacaoId"), agora);
            }
            return true;
        }
    }

    private static boolean vagaFutura(Document consulta, Date agora) {
        return consulta.getDate("inicio").after(agora);
    }

    private void avisarInteressados(Document consulta, Date agora, Map<ObjectId, Document> avisos) {
        ObjectId consultaId = consulta.getObjectId("_id");
        ObjectId medicoId = consulta.getObjectId("medicoId");
        Date inicio = consulta.getDate("inicio");
        Date fim = consulta.getDate("fim");
        String medicoNome = nomes.medicos(List.of(medicoId)).getOrDefault(medicoId, "o médico");

        fila.revisar(agora); // sinalizações de vagas que já passaram voltam a concorrer
        // Quem cancelou não é avisado da própria vaga; e cada paciente recebe no máximo um aviso por vaga.
        Set<ObjectId> pacientesConsiderados = new HashSet<>();
        pacientesConsiderados.add(consulta.getObjectId("pacienteId"));
        for (Document interesse : fila.candidatos(consulta.getObjectId("especialidadeId"), medicoId, inicio, agora)) {
            ObjectId pacienteId = interesse.getObjectId("pacienteId");
            if (!turnoCompativel(interesse.getString("turnoPreferido"), inicio.toInstant())) continue;
            if (!pacientesConsiderados.add(pacienteId)) continue;
            if (consultas.pacienteTemAtivaSobreposta(pacienteId, inicio, fim)) continue;

            ObjectId inscricao = interesse.getObjectId("_id");
            Document aviso = avisos.get(pacienteId);
            if (aviso == null) {
                aviso = notificacao(new ObjectId(), pacienteId, consultaId, inscricao, inicio, medicoNome, agora);
                if (!notificacoes.inserirSeNova(aviso))
                    throw new IllegalStateException("Aviso criado simultaneamente; retomar a sinalização");
                avisos.put(pacienteId, aviso);
            }
            if (!inscricao.equals(aviso.getObjectId("filaEsperaId")) || "CANCELADA".equals(aviso.getString("status"))
                    || interesse.getList("sinalizacoesPendentes", ObjectId.class, List.of()).contains(consultaId)) continue;
            Document ultima = new Document("consultaOrigemId", consultaId).append("medicoId", medicoId)
                    .append("dataHora", inicio).append("sinalizadaEm", agora).append("notificacaoId", aviso.getObjectId("_id"));
            fila.marcarSinalizada(inscricao, ultima, agora);
        }
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
            ObjectId notificacaoId = inscricao.get("ultimaSinalizacao", Document.class).getObjectId("notificacaoId");
            if (pacienteId.equals(inscricao.getObjectId("pacienteId"))) {
                if (fila.marcarAtendida(id, notificacaoId, consulta.getObjectId("_id"), agora)) alteradas++;
            } else if (devolverParaFila(id, notificacaoId, agora)) {
                alteradas++;
            }
        }
        return alteradas;
    }

    private boolean devolverParaFila(ObjectId inscricao, ObjectId aviso, Date agora) {
        if (!fila.voltarParaAguardando(inscricao, aviso, agora)) return false;
        notificacoes.descartar(aviso);
        return true;
    }

    /**
     * O horário de início da vaga atende ao turno preferido do interessado?
     * MANHA, TARDE ou QUALQUER (docs/dicionario/filaEspera.md).
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
    private boolean registrarHistorico(Document consulta, int total, Date agora) {
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
        if (!consultas.atualizarComEvento(id, versao, new Document("sinalizacaoFila", "LIMPAR"), evento)) {
            historico.processarConsulta(id);
            return false;
        }
        historico.processarConsulta(id);
        return true;
    }
}
