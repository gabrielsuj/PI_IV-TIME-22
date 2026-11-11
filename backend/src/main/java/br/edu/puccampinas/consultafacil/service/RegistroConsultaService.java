package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.domain.enums.EventoHistorico;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.domain.enums.StatusConsulta;
import br.edu.puccampinas.consultafacil.repository.ConsultaRepository;
import br.edu.puccampinas.consultafacil.repository.PendenciasConsultaRepository;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import org.bson.Document;
import org.bson.RawBsonDocument;
import org.bson.codecs.DocumentCodec;
import org.bson.types.ObjectId;
import java.time.Clock;
import java.util.*;

/**
 * Porta de gravação auditada para os futuros serviços de consultas.
 * O chamador valida disponibilidade, referências e acesso ao paciente/médico específico.
 * Não substitui as regras de agendamento e não deve ser exposto diretamente como endpoint.
 */
public final class RegistroConsultaService {
    public static final int LIMITE_BYTES_EVENTO = 16 * 1024;
    private static final Set<String> DADOS = Set.of("pacienteId", "medicoId", "especialidadeId", "inicio", "fim");
    private static final Set<String> EDITAVEIS = Set.of("pacienteId", "medicoId", "especialidadeId", "inicio", "fim", "status", "motivoCancelamento");
    private final PendenciasConsultaRepository consultas;
    private final HistoricoProcessor processor;
    private final Clock relogio;

    public RegistroConsultaService(PendenciasConsultaRepository consultas, HistoricoProcessor processor, Clock relogio) {
        this.consultas = consultas;
        this.processor = processor;
        this.relogio = relogio;
    }

    public Document criar(Document dados, UsuarioLogado ator) {
        validarAtor(ator);
        if (ator.perfil() == Perfil.MEDICO) throw ApiException.forbidden("Perfil não autorizado a criar consulta");
        if (dados == null || !DADOS.containsAll(dados.keySet())) throw ApiException.badRequest("Campos de criação inválidos");
        Document consulta = new Document(dados).append("_id", new ObjectId())
                .append("status", StatusConsulta.AGENDADA.name()).append("criadaEm", agora());
        validarDados(consulta);
        Document evento = evento(null, consulta, ator, EventoHistorico.CRIACAO, 1, List.of());
        consultas.inserirComEvento(consulta, evento);
        aposGravar(consulta.getObjectId("_id"));
        return consulta; // DTOs futuros não devem expor a caixa interna de pendências.
    }

    public Document alterar(ObjectId id, Document campos, UsuarioLogado ator) {
        validarAtor(ator);
        if (id == null || campos == null || !EDITAVEIS.containsAll(campos.keySet()))
            throw ApiException.badRequest("Campos de alteração inválidos");
        Document antes = consultas.buscar(id).orElseThrow(() -> ApiException.notFound("Consulta não encontrada"));
        StatusConsulta anterior = StatusConsulta.valueOf(antes.getString("status"));
        if (!StatusConsulta.ATIVOS.contains(anterior.name())) throw ApiException.conflict("Consulta em status final não pode ser alterada");
        Document depois = new Document(antes);
        Document modificados = new Document();
        List<Document> alteracoes = new ArrayList<>();
        for (String campo : new TreeSet<>(campos.keySet())) {
            Object valor = campos.get(campo);
            if (!Objects.equals(antes.get(campo), valor)) {
                modificados.put(campo, valor);
                depois.put(campo, valor);
                alteracoes.add(new Document("campo", campo).append("valorAnterior", antes.get(campo)).append("valorNovo", valor));
            }
        }
        validarDados(depois);
        StatusConsulta novo;
        try { novo = StatusConsulta.valueOf(depois.getString("status")); }
        catch (RuntimeException e) { throw ApiException.badRequest("Status inválido"); }
        EventoHistorico tipo = tipo(anterior, novo);
        validarPermissao(tipo, ator);
        if (modificados.isEmpty()) {
            aposGravar(id);
            return resposta(antes);
        }
        if (tipo != EventoHistorico.ALTERACAO && modificados.keySet().stream().anyMatch(DADOS::contains))
            throw ApiException.badRequest("Altere os dados e o status em operações separadas");
        if (modificados.containsKey("motivoCancelamento") && tipo != EventoHistorico.CANCELAMENTO)
            throw ApiException.badRequest("Motivo de cancelamento permitido somente ao cancelar");
        // Tenta esvaziar antes de recusar uma consulta que atingiu o teto.
        if (pendencias(antes) >= ConsultaRepository.LIMITE_PENDENCIAS) {
            processor.processarConsulta(id);
            antes = consultas.buscar(id).orElseThrow(() -> ApiException.notFound("Consulta não encontrada"));
            if (pendencias(antes) >= ConsultaRepository.LIMITE_PENDENCIAS)
                throw new ApiException(503, "Histórico pendente atingiu o limite; recupere as pendências antes de alterar esta consulta");
            // Outra operação pode ter ocorrido durante a recuperação: não usar snapshot antigo.
            if (!Objects.equals(antes.get("versao"), depois.get("versao")))
                throw ApiException.conflict("Consulta alterada simultaneamente; recarregue os dados");
        }
        int versao = antes.getInteger("versao", 0);
        int ultimaSequencia = versao == 0 ? processor.ultimaSequencia(id) : versao;
        if (ultimaSequencia == Integer.MAX_VALUE) throw ApiException.conflict("Limite de versão da consulta atingido");
        modificados.put("atualizadaEm", agora());
        depois.put("atualizadaEm", modificados.get("atualizadaEm"));
        Document evento = evento(antes, depois, ator, tipo, ultimaSequencia + 1, alteracoes);
        if (!consultas.atualizarComEvento(id, versao, modificados, evento))
            throw ApiException.conflict("Consulta alterada simultaneamente ou limite de pendências atingido; recarregue os dados");
        aposGravar(id);
        return resposta(depois);
    }

    private void aposGravar(ObjectId id) {
        // Falha de projeção não transforma uma gravação já confirmada em erro ao chamador.
        processor.processarConsulta(id);
        processor.tentarRecuperarLote();
    }

    private Document evento(Document antes, Document depois, UsuarioLogado ator, EventoHistorico tipo, int sequencia, List<Document> alteracoes) {
        Document e = new Document("_id", new ObjectId()).append("consultaId", depois.getObjectId("_id"))
                .append("sequencia", sequencia).append("evento", tipo.name())
                .append("statusAnterior", antes == null ? null : antes.getString("status"))
                .append("statusNovo", depois.getString("status"))
                .append("pacienteId", depois.getObjectId("pacienteId"))
                .append("medicoId", depois.getObjectId("medicoId"))
                .append("especialidadeId", depois.getObjectId("especialidadeId"))
                .append("dataHoraConsulta", depois.getDate("inicio")).append("fimConsulta", depois.getDate("fim"))
                .append("ator", new Document("perfil", ator.perfil().name()).append("id", new ObjectId(ator.id())))
                .append("ocorridoEm", agora());
        if (tipo == EventoHistorico.ALTERACAO) e.append("alteracoes", alteracoes);
        if (tipo == EventoHistorico.CANCELAMENTO && depois.get("motivoCancelamento") != null)
            e.append("motivo", depois.getString("motivoCancelamento"));
        if (new RawBsonDocument(e, new DocumentCodec()).getByteBuffer().remaining() > LIMITE_BYTES_EVENTO)
            throw ApiException.badRequest("Evento de histórico excede o tamanho permitido");
        return e;
    }

    private static EventoHistorico tipo(StatusConsulta antes, StatusConsulta depois) {
        if (antes == depois) return EventoHistorico.ALTERACAO;
        return switch (depois) {
            case CONFIRMADA -> {
                if (antes != StatusConsulta.AGENDADA) throw ApiException.conflict("Transição inválida");
                yield EventoHistorico.CONFIRMACAO;
            }
            case CANCELADA -> EventoHistorico.CANCELAMENTO;
            case CONCLUIDA -> EventoHistorico.CONCLUSAO;
            case FALTA -> EventoHistorico.FALTA;
            default -> throw ApiException.conflict("Transição de status inválida");
        };
    }

    private static void validarPermissao(EventoHistorico tipo, UsuarioLogado ator) {
        if (tipo == EventoHistorico.ALTERACAO && ator.perfil() != Perfil.SECRETARIA_ADMIN
                || (tipo == EventoHistorico.CONCLUSAO || tipo == EventoHistorico.FALTA) && ator.perfil() == Perfil.PACIENTE)
            throw ApiException.forbidden("Perfil não autorizado para esta alteração");
    }

    private static void validarAtor(UsuarioLogado ator) {
        if (ator == null || ator.perfil() == null || ator.id() == null || !ObjectId.isValid(ator.id()))
            throw ApiException.unauthorized("Usuário autenticado é obrigatório para registrar a consulta");
    }

    private static void validarDados(Document d) {
        for (String campo : List.of("pacienteId", "medicoId", "especialidadeId"))
            if (!(d.get(campo) instanceof ObjectId)) throw ApiException.badRequest(campo + " deve ser ObjectId");
        if (!(d.get("inicio") instanceof Date inicio) || !(d.get("fim") instanceof Date fim) || !inicio.before(fim))
            throw ApiException.badRequest("Intervalo da consulta inválido");
        if (d.containsKey("motivoCancelamento") && (!(d.get("motivoCancelamento") instanceof String motivo) || motivo.length() > 255))
            throw ApiException.badRequest("Motivo deve ser texto com até 255 caracteres");
    }

    private static int pendencias(Document d) { return d.getList("eventosPendentes", Document.class, List.of()).size(); }
    private Date agora() { return Date.from(relogio.instant()); }
    private static Document resposta(Document d) {
        Document resultado = new Document(d);
        resultado.remove("eventosPendentes");
        resultado.remove("versao");
        return resultado;
    }
}
