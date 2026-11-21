package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.config.AppConfig;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.domain.enums.StatusConsulta;
import br.edu.puccampinas.consultafacil.repository.ConsultaRepository;
import br.edu.puccampinas.consultafacil.repository.NomesRepository;
import br.edu.puccampinas.consultafacil.repository.NotificacaoRepository;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

/**
 * Lembretes e notificações simuladas (#34). Modelo em docs/modelagem/notificacoes.md.
 *
 * Geração:
 *  - consulta agendada  -> CONFIRMACAO_AGENDAMENTO (imediata) e LEMBRETE_CONSULTA (início − antecedência) ao paciente;
 *  - consulta cancelada -> lembretes/confirmações pendentes passam a CANCELADA e CANCELAMENTO_CONSULTA
 *                          vai ao paciente e ao médico, exceto ao perfil que cancelou.
 * Envio: {@link #executarCiclo()} roda periodicamente no servidor. Ele cria os lembretes que faltam para as
 * consultas que entram na janela de antecedência e entrega as notificações vencidas (status AGENDADA -> ENVIADA).
 * Antes de entregar um lembrete, relê a consulta: se ela não está mais ativa, mudou de horário ou já começou,
 * o lembrete é descartado.
 *
 * Duplicidade: o índice único uq_notif_idempotencia (consulta, tipo, destinatário, canal, antecedência)
 * faz o reprocessamento ser seguro; inserirSeNova devolve false quando a notificação já existe.
 * Os métodos públicos não lançam exceção: a consulta já foi gravada e o aviso não pode desfazê-la.
 */
public final class NotificacoesService {
    private static final Logger LOG = LoggerFactory.getLogger(NotificacoesService.class);
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm").withZone(FUSO);
    private static final int LOTE = 100;

    private final NotificacaoRepository notificacoes;
    private final ConsultaRepository consultas;
    private final NomesRepository nomes;
    private final EntregaEmailTeste email;
    private final AppConfig.Notificacoes config;
    private final Clock relogio;

    public NotificacoesService(NotificacaoRepository notificacoes, ConsultaRepository consultas, NomesRepository nomes,
                               EntregaEmailTeste email, AppConfig.Notificacoes config, Clock relogio) {
        this.notificacoes = notificacoes;
        this.consultas = consultas;
        this.nomes = nomes;
        this.email = email;
        this.config = config;
        this.relogio = relogio;
    }

    // ---------------------------------------------------------------- eventos da consulta

    /** Chamado pelo RegistroConsultaService logo depois de gravar uma consulta nova. */
    public void consultaAgendada(Document consulta) {
        try {
            Date agora = agora();
            String medico = nomeDoMedico(consulta);
            String quando = DATA_HORA.format(consulta.getDate("inicio").toInstant());
            criar(paraPaciente(consulta, "CONFIRMACAO_AGENDAMENTO", medico, agora, null, agora,
                    "Consulta agendada", "Sua consulta com " + medico + " foi agendada para " + quando + "."));
            criarLembrete(consulta, medico, agora);
            enviarVencidas(); // a confirmação aparece na central sem esperar o próximo ciclo
        } catch (RuntimeException e) {
            LOG.warn("Notificações do agendamento da consulta {} adiadas ({})", consulta.getObjectId("_id"), e.toString());
        }
    }

    /** Gancho do cancelamento (#27). Quem cancelou não recebe aviso do próprio cancelamento. */
    public void consultaCancelada(Document consulta, UsuarioLogado quemCancelou) {
        try {
            ObjectId id = consulta.getObjectId("_id");
            notificacoes.cancelarPendentesDaConsulta(id);
            Date agora = agora();
            String medico = nomeDoMedico(consulta);
            String quando = DATA_HORA.format(consulta.getDate("inicio").toInstant());
            Perfil perfil = quemCancelou == null ? null : quemCancelou.perfil();
            if (perfil != Perfil.PACIENTE)
                criar(paraPaciente(consulta, "CANCELAMENTO_CONSULTA", medico, agora, null, agora, "Consulta cancelada",
                        "Sua consulta com " + medico + " em " + quando + " foi cancelada. Se precisar, agende um novo horário."));
            if (perfil != Perfil.MEDICO)
                criar(base(Perfil.MEDICO, consulta.getObjectId("medicoId"), consulta, "CANCELAMENTO_CONSULTA",
                        StatusConsulta.CANCELADA, medico, agora, null, agora, "Consulta cancelada",
                        "A consulta de " + quando + " da sua agenda foi cancelada. O horário voltou a ficar disponível."));
            enviarVencidas();
        } catch (RuntimeException e) {
            LOG.warn("Notificações do cancelamento da consulta {} adiadas ({})", consulta.getObjectId("_id"), e.toString());
        }
    }

    // ---------------------------------------------------------------- agendador

    /** Uma execução do agendador: completa os lembretes da janela e entrega o que venceu. */
    public void executarCiclo() {
        try {
            gerarLembretesDaJanela();
            enviarVencidas();
        } catch (RuntimeException e) {
            LOG.warn("Ciclo de notificações adiado ({})", e.toString());
        }
    }

    /**
     * Consultas ativas que começam dentro da antecedência e ainda não têm lembrete (ex.: carga de demonstração
     * ou falha no momento do agendamento) ganham o lembrete agora. Retorna quantos foram criados.
     */
    int gerarLembretesDaJanela() {
        Date agora = agora();
        Date limite = Date.from(agora.toInstant().plus(antecedencia()));
        int criados = 0;
        for (Document consulta : consultas.ativasComInicioEntre(agora, limite, LOTE)) {
            if (notificacoes.temLembrete(consulta.getObjectId("_id"))) continue;
            if (criarLembrete(consulta, nomeDoMedico(consulta), agora)) criados++;
        }
        return criados;
    }

    /** Entrega as notificações vencidas. synchronized: o agendador e as requisições não entregam a mesma duas vezes. */
    synchronized int enviarVencidas() {
        Date agora = agora();
        int enviadas = 0;
        for (Document n : notificacoes.vencidas(agora, LOTE)) {
            ObjectId id = n.getObjectId("_id");
            if (!aindaFazSentido(n, agora)) {
                notificacoes.descartar(id);
                continue;
            }
            try {
                if ("EMAIL_TESTE".equals(n.getString("canal"))) email.entregar(n);
                if (notificacoes.marcarEnviada(id, agora)) enviadas++;
            } catch (RuntimeException e) {
                LOG.warn("Envio da notificação {} falhou ({})", id, e.getMessage());
                notificacoes.registrarFalha(n, e.getMessage());
            }
        }
        return enviadas;
    }

    /** Lembrete e confirmação só são entregues se a consulta continua ativa, no mesmo horário e no futuro. */
    private boolean aindaFazSentido(Document n, Date agora) {
        String tipo = n.getString("tipo");
        if (!"LEMBRETE_CONSULTA".equals(tipo) && !"CONFIRMACAO_AGENDAMENTO".equals(tipo)) return true;
        Document consulta = consultas.buscar(n.getObjectId("consultaId")).orElse(null);
        if (consulta == null || !StatusConsulta.ATIVOS.contains(consulta.getString("status"))) return false;
        Date inicio = consulta.getDate("inicio");
        return inicio.equals(n.get("dadosConsulta", Document.class).getDate("dataHora")) && inicio.after(agora);
    }

    // ---------------------------------------------------------------- montagem

    /**
     * Lembrete programado para início − antecedência. Se esse momento já passou (consulta marcada em cima da hora),
     * é entregue no próximo envio, desde que a consulta ainda não tenha começado.
     */
    private boolean criarLembrete(Document consulta, String medico, Date agora) {
        Instant inicio = consulta.getDate("inicio").toInstant();
        if (!inicio.isAfter(agora.toInstant())) return false;
        Instant programado = inicio.minus(antecedencia());
        Date agendadaPara = programado.isAfter(agora.toInstant()) ? Date.from(programado) : agora;
        StatusConsulta status = StatusConsulta.valueOf(consulta.getString("status"));
        Document lembrete = base(Perfil.PACIENTE, consulta.getObjectId("pacienteId"), consulta, "LEMBRETE_CONSULTA",
                status, medico, agendadaPara, config.antecedenciaHoras(), agora, "Lembrete de consulta",
                "Sua consulta com " + medico + " é em " + DATA_HORA.format(inicio)
                        + ". Confirme sua presença pelo ConsultaFácil.");
        return criar(lembrete);
    }

    private Document paraPaciente(Document consulta, String tipo, String medico, Date agendadaPara, Integer antecedencia,
                                  Date agora, String titulo, String mensagem) {
        StatusConsulta status = "CANCELAMENTO_CONSULTA".equals(tipo) ? StatusConsulta.CANCELADA : StatusConsulta.AGENDADA;
        return base(Perfil.PACIENTE, consulta.getObjectId("pacienteId"), consulta, tipo, status, medico,
                agendadaPara, antecedencia, agora, titulo, mensagem);
    }

    /**
     * Documento no formato do validador de database/init/09-esquema-notificacoes.js.
     * A mensagem traz só médico, data e horário: sem CPF, motivo ou dado de saúde.
     */
    private static Document base(Perfil perfil, ObjectId destinatario, Document consulta, String tipo,
                                 StatusConsulta statusConsulta, String medico, Date agendadaPara, Integer antecedencia,
                                 Date agora, String titulo, String mensagem) {
        return new Document("destinatarioPerfil", perfil.name()).append("destinatarioId", destinatario)
                .append("tipo", tipo).append("canal", "SISTEMA").append("simulada", true)
                .append("consultaId", consulta.getObjectId("_id")).append("filaEsperaId", null)
                .append("statusConsulta", statusConsulta.name()).append("antecedenciaHoras", antecedencia)
                .append("titulo", titulo).append("mensagem", mensagem)
                .append("dadosConsulta", new Document("dataHora", consulta.getDate("inicio")).append("medicoNome", medico))
                .append("status", "AGENDADA").append("agendadaPara", agendadaPara).append("enviadaEm", null)
                .append("tentativas", 0).append("erro", null).append("lidaEm", null).append("confirmadaEm", null)
                .append("criadoEm", agora);
    }

    /** Insere no sistema e, se configurado, uma cópia no canal EMAIL_TESTE (só para pacientes). */
    private boolean criar(Document notificacao) {
        boolean nova = notificacoes.inserirSeNova(notificacao);
        if (config.emailTeste() && "PACIENTE".equals(notificacao.getString("destinatarioPerfil"))) {
            Document copia = new Document(notificacao);
            copia.remove("_id");
            notificacoes.inserirSeNova(copia.append("canal", "EMAIL_TESTE"));
        }
        return nova;
    }

    private String nomeDoMedico(Document consulta) {
        ObjectId medicoId = consulta.getObjectId("medicoId");
        String nome = nomes.medicos(List.of(medicoId)).get(medicoId);
        return nome == null || nome.isBlank() ? "o médico" : nome;
    }

    private Duration antecedencia() { return Duration.ofHours(config.antecedenciaHoras()); }
    private Date agora() { return Date.from(relogio.instant()); }
}
