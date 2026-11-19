package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.AgendamentoRequest;
import br.edu.puccampinas.consultafacil.api.dto.AvisoVagaResponse;
import br.edu.puccampinas.consultafacil.api.dto.AvisoVagaResponse.Status;
import br.edu.puccampinas.consultafacil.api.dto.ConsultaResponse;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.domain.enums.StatusConsulta;
import br.edu.puccampinas.consultafacil.repository.ConsultaRepository;
import br.edu.puccampinas.consultafacil.repository.FilaEsperaRepository;
import br.edu.puccampinas.consultafacil.repository.NotificacaoRepository;
import br.edu.puccampinas.consultafacil.repository.PacienteDoUsuarioRepository;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

public final class AvisoVagaService {
    private static final String AVISO_INDISPONIVEL = "A vaga deste aviso expirou ou já foi ocupada.";

    private final NotificacaoRepository notificacoes;
    private final FilaEsperaRepository fila;
    private final ConsultaRepository consultas;
    private final PacienteDoUsuarioRepository pacienteDoUsuario;
    private final SinalizacaoFilaService sinalizacao;
    private final AgendamentoService agendamento;
    private final Clock relogio;

    public AvisoVagaService(NotificacaoRepository notificacoes, FilaEsperaRepository fila, ConsultaRepository consultas,
                            PacienteDoUsuarioRepository pacienteDoUsuario, SinalizacaoFilaService sinalizacao,
                            AgendamentoService agendamento, Clock relogio) {
        this.notificacoes = notificacoes;
        this.fila = fila;
        this.consultas = consultas;
        this.pacienteDoUsuario = pacienteDoUsuario;
        this.sinalizacao = sinalizacao;
        this.agendamento = agendamento;
        this.relogio = relogio;
    }

    public List<AvisoVagaResponse> listar(UsuarioLogado ator) {
        return notificacoes.avisosDeVaga(pacienteDe(ator)).stream()
                .map(aviso -> situacao(aviso, origemDe(aviso))).toList();
    }

    public ConsultaResponse agendar(ObjectId id, UsuarioLogado ator) {
        Document aviso = notificacoes.buscarAvisoDeVaga(id, pacienteDe(ator))
                .orElseThrow(() -> ApiException.notFound("Aviso de vaga não encontrado"));
        Document origem = origemDe(aviso);
        AvisoVagaResponse atual = situacao(aviso, origem);
        if (atual.status() != Status.DISPONIVEL) throw ApiException.conflict(AVISO_INDISPONIVEL);

        // A vaga é determinada no servidor pelo aviso. O agendamento normal revalida a agenda
        // e o índice de consultas garante que apenas um pedido concorrente seja confirmado.
        var pedido = new AgendamentoRequest(null, origem.getObjectId("medicoId").toHexString(),
                origem.getObjectId("especialidadeId").toHexString(), atual.inicio(), atual.fim());
        try {
            return agendamento.agendar(pedido, ator);
        } catch (ApiException e) {
            if (e.status() == 409 && situacao(aviso, origem).status() == Status.EXPIRADO)
                throw ApiException.conflict(AVISO_INDISPONIVEL);
            throw e;
        }
    }

    private AvisoVagaResponse situacao(Document aviso, Document origem) {
        ObjectId id = aviso.getObjectId("_id");
        Document dados = aviso.get("dadosConsulta", Document.class);
        Instant inicio = dados.getDate("dataHora").toInstant();
        String fim = origem == null ? null : origem.getDate("fim").toInstant().toString();
        Document interesse = fila.buscar(aviso.getObjectId("filaEsperaId")).orElse(null);
        Instant validade = inicio;
        if (interesse != null && interesse.getDate("validoAte").toInstant().isBefore(inicio))
            validade = interesse.getDate("validoAte").toInstant();

        Status status = Status.EXPIRADO;
        ObjectId consultaAgendada = null;
        if (origem != null && interesse != null && !"CANCELADA".equals(aviso.getString("status"))) {
            if (sinalizacaoAtual(interesse, id) && validade.isAfter(relogio.instant())
                    && StatusConsulta.CANCELADA.name().equals(origem.getString("status"))) {
                if (!sinalizacao.reconciliarVaga(origem)) {
                    status = Status.DISPONIVEL;
                } else {
                    interesse = fila.buscar(aviso.getObjectId("filaEsperaId")).orElse(null);
                }
            }
            consultaAgendada = consultaQueAtendeu(interesse, origem);
            if (consultaAgendada != null) status = Status.AGENDADO;
        }
        return new AvisoVagaResponse(id.toHexString(), dados.getString("medicoNome"), inicio.toString(),
                fim, validade.toString(), status, consultaAgendada == null ? null : consultaAgendada.toHexString());
    }

    private static boolean sinalizacaoAtual(Document interesse, ObjectId id) {
        Document ultima = interesse.get("ultimaSinalizacao", Document.class);
        return "SINALIZADA".equals(interesse.getString("status")) && ultima != null
                && id.equals(ultima.getObjectId("notificacaoId"));
    }

    private ObjectId consultaQueAtendeu(Document interesse, Document origem) {
        if (interesse == null || !"ATENDIDA".equals(interesse.getString("status"))) return null;
        ObjectId consultaId = interesse.getObjectId("consultaAgendadaId");
        return consultas.buscar(consultaId)
                .filter(c -> origem.getObjectId("medicoId").equals(c.getObjectId("medicoId"))
                        && origem.getDate("inicio").equals(c.getDate("inicio")))
                .map(c -> consultaId).orElse(null);
    }

    private Document origemDe(Document aviso) {
        return consultas.buscar(aviso.getObjectId("consultaId")).orElse(null);
    }

    private ObjectId pacienteDe(UsuarioLogado ator) {
        if (ator == null || ator.perfil() != Perfil.PACIENTE)
            throw ApiException.forbidden("Os avisos de vaga são exclusivos do paciente");
        return pacienteDoUsuario.buscar(ator.id())
                .orElseThrow(() -> ApiException.forbidden("Seu login não está ligado a um cadastro de paciente"));
    }
}
