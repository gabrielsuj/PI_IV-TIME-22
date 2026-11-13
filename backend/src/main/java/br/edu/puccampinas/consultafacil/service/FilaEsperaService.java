package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.api.dto.EspecialidadeResponse;
import br.edu.puccampinas.consultafacil.api.dto.FilaEsperaRequest;
import br.edu.puccampinas.consultafacil.api.dto.FilaEsperaResponse;
import br.edu.puccampinas.consultafacil.api.dto.MedicoResponse;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.repository.EspecialidadeRepository;
import br.edu.puccampinas.consultafacil.repository.FilaEsperaRepository;
import br.edu.puccampinas.consultafacil.repository.MedicoRepository;
import br.edu.puccampinas.consultafacil.repository.NomesRepository;
import br.edu.puccampinas.consultafacil.repository.PacienteDoUsuarioRepository;
import br.edu.puccampinas.consultafacil.repository.PacienteRepository;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Inscrição do paciente na fila de espera (#31): entrar, consultar e sair.
 * Paciente age só para si mesmo; a secretaria age por qualquer paciente. O médico não usa a fila.
 * Regras do modelo: docs/modelagem/filaEspera.md. Contrato: docs/api/fila-espera.md.
 */
public class FilaEsperaService {
    /** Validade da inscrição sem dataFim, e limite para a dataFim (sugestão do modelo: 90 dias). */
    public static final int DIAS_VALIDADE = 90;
    static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final Set<String> TURNOS = Set.of("MANHA", "TARDE", "QUALQUER");
    private static final Set<String> STATUS = Set.of("AGUARDANDO", "SINALIZADA", "ATENDIDA", "CANCELADA", "EXPIRADA");

    private final FilaEsperaRepository fila;
    private final EspecialidadeRepository especialidades;
    private final MedicoRepository medicos;
    private final PacienteRepository pacientes;
    private final PacienteDoUsuarioRepository pacienteDoUsuario;
    private final NomesRepository nomes;
    private final Clock relogio;

    public FilaEsperaService(FilaEsperaRepository fila, EspecialidadeRepository especialidades, MedicoRepository medicos,
                             PacienteRepository pacientes, PacienteDoUsuarioRepository pacienteDoUsuario,
                             NomesRepository nomes, Clock relogio) {
        this.fila = fila;
        this.especialidades = especialidades;
        this.medicos = medicos;
        this.pacientes = pacientes;
        this.pacienteDoUsuario = pacienteDoUsuario;
        this.nomes = nomes;
        this.relogio = relogio;
    }

    // ------------------------------------------------------------------ POST /fila-espera

    public FilaEsperaResponse inscrever(FilaEsperaRequest r, UsuarioLogado ator) {
        if (r == null) throw ApiException.badRequest("Corpo da requisição vazio");
        ObjectId pacienteId = pacienteDaInscricao(r.pacienteId(), ator);
        ObjectId especialidadeId = Query.objectId(r.especialidadeId(), "especialidadeId");
        ObjectId medicoId = vazio(r.medicoId()) ? null : Query.objectId(r.medicoId(), "medicoId");
        String turno = vazio(r.turnoPreferido()) ? "QUALQUER" : r.turnoPreferido().strip().toUpperCase();
        if (!TURNOS.contains(turno)) throw ApiException.badRequest("turnoPreferido deve ser MANHA, TARDE ou QUALQUER");
        validarEspecialidadeEMedico(especialidadeId, medicoId);

        Instant agora = relogio.instant();
        LocalDate hoje = agora.atZone(FUSO).toLocalDate();
        LocalDate inicio = vazio(r.dataInicio()) ? null : data(r.dataInicio(), "dataInicio");
        LocalDate fim = vazio(r.dataFim()) ? null : data(r.dataFim(), "dataFim");
        if (inicio != null && inicio.isBefore(hoje)) throw ApiException.badRequest("dataInicio não pode estar no passado");
        if (fim != null && fim.isBefore(inicio != null ? inicio : hoje))
            throw ApiException.badRequest("dataFim deve ser igual ou posterior a dataInicio e a hoje");
        if (fim != null && fim.isAfter(hoje.plusDays(DIAS_VALIDADE)))
            throw ApiException.badRequest("O período da fila vai no máximo " + DIAS_VALIDADE + " dias à frente");

        Date criadoEm = Date.from(agora);
        // A inscrição vale até o fim do último dia do período ou, sem período, por 90 dias.
        Date validoAte = fim != null ? fimDoDia(fim) : Date.from(agora.plus(Duration.ofDays(DIAS_VALIDADE)));
        Document d = new Document("_id", new ObjectId())
                .append("pacienteId", pacienteId)
                .append("especialidadeId", especialidadeId)
                .append("medicoId", medicoId) // null gravado explicitamente = qualquer médico
                .append("turnoPreferido", turno)
                .append("status", "AGUARDANDO")
                .append("ultimaSinalizacao", null)
                .append("consultaAgendadaId", null)
                .append("atendidaEm", null)
                .append("validoAte", validoAte)
                .append("registradoPor", new Document("perfil", ator.perfil() == Perfil.PACIENTE ? "PACIENTE" : "SECRETARIA")
                        .append("id", new ObjectId(ator.id())))
                .append("criadoEm", criadoEm)
                .append("atualizadoEm", criadoEm);
        if (inicio != null) d.append("dataInicio", Date.from(inicio.atStartOfDay(FUSO).toInstant()));
        if (fim != null) d.append("dataFim", fimDoDia(fim));

        fila.revisar(criadoEm); // uma inscrição vencida não pode bloquear a nova pelo índice único
        return respostas(List.of(fila.inserir(d))).get(0);
    }

    // ------------------------------------------------------------------ GET /fila-espera

    /** Paciente: as próprias. Secretaria: todas, com filtros opcionais. Sem status: só as ativas. */
    public List<FilaEsperaResponse> listar(String status, String pacienteIdTexto, String especialidadeIdTexto,
                                           UsuarioLogado ator) {
        exigirPerfil(ator);
        List<Bson> filtros = new ArrayList<>();
        if (vazio(status)) filtros.add(Filters.in("status", FilaEsperaRepository.ATIVOS));
        else {
            String s = status.strip().toUpperCase();
            if (!STATUS.contains(s)) throw ApiException.badRequest("status inválido");
            filtros.add(Filters.eq("status", s));
        }
        if (ator.perfil() == Perfil.PACIENTE) filtros.add(Filters.eq("pacienteId", pacienteDe(ator)));
        else {
            if (!vazio(pacienteIdTexto)) filtros.add(Filters.eq("pacienteId", Query.objectId(pacienteIdTexto, "pacienteId")));
            if (!vazio(especialidadeIdTexto))
                filtros.add(Filters.eq("especialidadeId", Query.objectId(especialidadeIdTexto, "especialidadeId")));
        }
        fila.revisar(Date.from(relogio.instant()));
        return respostas(fila.listar(Filters.and(filtros)));
    }

    // ------------------------------------------------------------------ DELETE /fila-espera/{id}

    public FilaEsperaResponse sair(ObjectId id, UsuarioLogado ator) {
        exigirPerfil(ator);
        Document inscricao = fila.buscar(id).orElseThrow(() -> ApiException.notFound("Inscrição não encontrada"));
        if (ator.perfil() == Perfil.PACIENTE && !pacienteDe(ator).equals(inscricao.getObjectId("pacienteId")))
            throw ApiException.forbidden("O paciente só pode remover as próprias inscrições");
        if (!fila.cancelar(id, Date.from(relogio.instant())))
            throw ApiException.conflict("Esta inscrição já foi encerrada");
        return respostas(List.of(fila.buscar(id).orElseThrow())).get(0);
    }

    // ------------------------------------------------------------------ regras

    /**
     * Paciente se inscreve só a si mesmo; a secretaria informa o paciente.
     * Mesma regra do agendamento (AgendamentoService.pacienteDaConsulta).
     */
    private ObjectId pacienteDaInscricao(String pacienteIdTexto, UsuarioLogado ator) {
        exigirPerfil(ator);
        if (ator.perfil() == Perfil.PACIENTE) {
            ObjectId proprio = pacienteDe(ator);
            if (!vazio(pacienteIdTexto) && !pacienteIdTexto.equals(proprio.toHexString()))
                throw ApiException.forbidden("O paciente só pode se inscrever na fila para si mesmo");
            return proprio;
        }
        if (vazio(pacienteIdTexto)) throw ApiException.badRequest("Informe o paciente da inscrição (pacienteId)");
        ObjectId pacienteId = Query.objectId(pacienteIdTexto, "pacienteId");
        if (pacientes.consultar(pacienteId.toHexString()).isEmpty()) throw ApiException.notFound("Paciente não encontrado");
        return pacienteId;
    }

    private void validarEspecialidadeEMedico(ObjectId especialidadeId, ObjectId medicoId) {
        Optional<EspecialidadeResponse> especialidade = especialidades.consultar(especialidadeId);
        if (especialidade.isEmpty() || !especialidade.get().ativa())
            throw ApiException.notFound("Especialidade não encontrada ou inativa");
        if (medicoId == null) return;
        Optional<MedicoResponse> medico = medicos.consultar(medicoId);
        if (medico.isEmpty() || !medico.get().ativo()) throw ApiException.notFound("Médico não encontrado ou inativo");
        if (!medico.get().especialidadeIds().contains(especialidadeId.toHexString()))
            throw ApiException.badRequest("O médico não atende esta especialidade");
    }

    /** O médico não usa a fila (Rotas já barra; aqui é a segunda linha de defesa). */
    private static void exigirPerfil(UsuarioLogado ator) {
        if (ator == null || ator.perfil() == null) throw ApiException.unauthorized("Faça login para usar a fila de espera");
        if (ator.perfil() == Perfil.MEDICO) throw ApiException.forbidden("A fila de espera é do paciente e da secretaria");
    }

    private ObjectId pacienteDe(UsuarioLogado ator) {
        return pacienteDoUsuario.buscar(ator.id()).orElseThrow(() ->
                ApiException.forbidden("Seu login não está ligado a um cadastro de paciente. Procure a secretaria."));
    }

    // ------------------------------------------------------------------ resposta

    /** Monta as respostas buscando os nomes em lote (uma consulta por coleção). */
    private List<FilaEsperaResponse> respostas(List<Document> docs) {
        Map<ObjectId, String> nomesPacientes = nomes.pacientes(ids(docs, "pacienteId"));
        Map<ObjectId, String> nomesEspecialidades = nomes.especialidades(ids(docs, "especialidadeId"));
        Set<ObjectId> idsMedicos = new HashSet<>(ids(docs, "medicoId"));
        for (Document d : docs) {
            Document s = d.get("ultimaSinalizacao", Document.class);
            if (s != null) idsMedicos.add(s.getObjectId("medicoId"));
        }
        Map<ObjectId, String> nomesMedicos = nomes.medicos(idsMedicos);

        List<FilaEsperaResponse> lista = new ArrayList<>();
        for (Document d : docs) {
            ObjectId medicoId = d.getObjectId("medicoId");
            Document s = d.get("ultimaSinalizacao", Document.class);
            FilaEsperaResponse.Sinalizacao sinalizacao = s == null ? null : new FilaEsperaResponse.Sinalizacao(
                    s.getObjectId("consultaOrigemId").toHexString(), s.getObjectId("medicoId").toHexString(),
                    nomesMedicos.get(s.getObjectId("medicoId")), iso(s.getDate("dataHora")), iso(s.getDate("sinalizadaEm")));
            lista.add(new FilaEsperaResponse(
                    d.getObjectId("_id").toHexString(),
                    d.getObjectId("pacienteId").toHexString(), nomesPacientes.get(d.getObjectId("pacienteId")),
                    d.getObjectId("especialidadeId").toHexString(), nomesEspecialidades.get(d.getObjectId("especialidadeId")),
                    medicoId == null ? null : medicoId.toHexString(), medicoId == null ? null : nomesMedicos.get(medicoId),
                    d.getString("turnoPreferido"),
                    dataLocal(d.getDate("dataInicio")), dataLocal(d.getDate("dataFim")),
                    d.getString("status"), iso(d.getDate("validoAte")), iso(d.getDate("criadoEm")), sinalizacao));
        }
        return lista;
    }

    private static Collection<ObjectId> ids(List<Document> docs, String campo) {
        return docs.stream().map(d -> d.getObjectId(campo)).filter(Objects::nonNull).distinct().toList();
    }

    private static boolean vazio(String s) { return s == null || s.isBlank(); }

    private static LocalDate data(String valor, String campo) {
        try {
            return LocalDate.parse(valor.strip());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(campo + " deve estar no formato AAAA-MM-DD");
        }
    }

    /** Último milissegundo do dia em São Paulo. */
    private static Date fimDoDia(LocalDate dia) {
        return Date.from(dia.plusDays(1).atStartOfDay(FUSO).toInstant().minusMillis(1));
    }

    private static String dataLocal(Date d) { return d == null ? null : d.toInstant().atZone(FUSO).toLocalDate().toString(); }

    private static String iso(Date d) { return d == null ? null : d.toInstant().toString(); }
}
