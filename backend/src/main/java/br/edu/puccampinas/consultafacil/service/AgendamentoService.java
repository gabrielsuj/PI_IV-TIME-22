package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.api.dto.AgendamentoRequest;
import br.edu.puccampinas.consultafacil.api.dto.ConsultaResponse;
import br.edu.puccampinas.consultafacil.api.dto.EspecialidadeResponse;
import br.edu.puccampinas.consultafacil.api.dto.MedicoResponse;
import br.edu.puccampinas.consultafacil.api.dto.VagaDto;
import br.edu.puccampinas.consultafacil.api.dto.VagasResponse;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.repository.EspecialidadeRepository;
import br.edu.puccampinas.consultafacil.repository.MedicoRepository;
import br.edu.puccampinas.consultafacil.repository.PacienteDoUsuarioRepository;
import br.edu.puccampinas.consultafacil.repository.PacienteRepository;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import br.edu.puccampinas.consultafacil.service.CalculadoraDisponibilidade.Vaga;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Busca de vagas e confirmação do agendamento (#21), usando o cálculo de disponibilidade (#20).
 *
 * A gravação passa pelo RegistroConsultaService, que registra o histórico (#29).
 * Se duas pessoas confirmarem a mesma vaga ao mesmo tempo, o índice único de `consultas`
 * aceita só uma e a outra recebe 409 (ver docs/modelagem/consultas.md).
 */
public class AgendamentoService {
    /** Maior intervalo aceito na busca de vagas, em dias. */
    public static final int MAX_DIAS_BUSCA = 31;
    public static final String HORARIO_INDISPONIVEL =
            "Este horário não está mais disponível. Escolha outro horário na lista atualizada.";

    private final DisponibilidadeService disponibilidade;
    private final MedicoRepository medicos;
    private final EspecialidadeRepository especialidades;
    private final PacienteRepository pacientes;
    private final PacienteDoUsuarioRepository pacienteDoUsuario;
    private final RegistroConsultaService registro;

    public AgendamentoService(DisponibilidadeService disponibilidade, MedicoRepository medicos,
                              EspecialidadeRepository especialidades, PacienteRepository pacientes,
                              PacienteDoUsuarioRepository pacienteDoUsuario, RegistroConsultaService registro) {
        this.disponibilidade = disponibilidade;
        this.medicos = medicos;
        this.especialidades = especialidades;
        this.pacientes = pacientes;
        this.pacienteDoUsuario = pacienteDoUsuario;
        this.registro = registro;
    }

    // ------------------------------------------------------------------ GET /vagas

    /** Vagas do médico entre de e ate (AAAA-MM-DD, inclusivos), na especialidade pedida. */
    public VagasResponse buscarVagas(String medicoIdTexto, String especialidadeIdTexto, String de, String ate) {
        ObjectId medicoId = Query.objectId(medicoIdTexto, "medicoId");
        ObjectId especialidadeId = Query.objectId(especialidadeIdTexto, "especialidadeId");
        LocalDate inicio = paraData(de, "data");
        LocalDate fim = paraData(ate, "data");
        if (fim.isBefore(inicio)) throw ApiException.badRequest("A data final deve ser igual ou posterior à inicial");
        if (inicio.plusDays(MAX_DIAS_BUSCA - 1).isBefore(fim))
            throw ApiException.badRequest("Busque no máximo " + MAX_DIAS_BUSCA + " dias por vez");

        validarMedicoEEspecialidade(medicoId, especialidadeId);

        String fuso = AgendaService.FUSO_PADRAO;
        boolean semAgenda = false;
        List<VagaDto> horarios = new ArrayList<>();
        for (LocalDate dia = inicio; !dia.isAfter(fim); dia = dia.plusDays(1)) {
            DisponibilidadeService.Resultado resultado = disponibilidade.doDia(medicoId, dia);
            fuso = resultado.fusoHorario();
            semAgenda = resultado.semAgenda();
            for (Vaga vaga : resultado.vagas()) {
                horarios.add(new VagaDto(vaga.inicio().toString(), vaga.fim().toString()));
            }
        }
        return new VagasResponse(medicoId.toHexString(), fuso, semAgenda, horarios);
    }

    // ------------------------------------------------------------------ POST /consultas

    /** Confirma o agendamento. Revalida a disponibilidade agora, e não no momento da busca. */
    public ConsultaResponse agendar(AgendamentoRequest r, UsuarioLogado ator) {
        if (r == null) throw ApiException.badRequest("Corpo da requisição vazio");
        ObjectId medicoId = Query.objectId(r.medicoId(), "medicoId");
        ObjectId especialidadeId = Query.objectId(r.especialidadeId(), "especialidadeId");
        Instant inicio = paraInstante(r.inicio(), "inicio");
        Instant fim = paraInstante(r.fim(), "fim");

        validarMedicoEEspecialidade(medicoId, especialidadeId);
        ObjectId pacienteId = pacienteDaConsulta(r.pacienteId(), ator);

        // Outra pessoa pode ter reservado depois da busca, ou o horário saiu da jornada / foi bloqueado.
        if (!disponibilidade.estaLivre(medicoId, inicio, fim))
            throw ApiException.conflict(HORARIO_INDISPONIVEL);
        // TODO #25: recusar sobreposição com outra consulta ativa do mesmo paciente em outro médico
        //           (hoje o índice uk_consulta_paciente_inicio só barra o mesmo instante de início).

        Document dados = new Document("pacienteId", pacienteId)
                .append("medicoId", medicoId)
                .append("especialidadeId", especialidadeId)
                .append("inicio", Date.from(inicio))
                .append("fim", Date.from(fim));
        return ConsultaResponse.de(registro.criar(dados, ator));
    }

    /** Paciente agenda para si mesmo; a secretaria informa para quem é a consulta. */
    private ObjectId pacienteDaConsulta(String pacienteIdTexto, UsuarioLogado ator) {
        if (ator != null && ator.perfil() == Perfil.PACIENTE) {
            Optional<ObjectId> proprio = pacienteDoUsuario.buscar(ator.id());
            if (proprio.isEmpty())
                throw ApiException.forbidden("Seu login não está ligado a um cadastro de paciente. Procure a secretaria.");
            if (pacienteIdTexto != null && !pacienteIdTexto.equals(proprio.get().toHexString()))
                throw ApiException.forbidden("O paciente só pode agendar consultas para si mesmo");
            return proprio.get();
        }

        if (pacienteIdTexto == null || pacienteIdTexto.isBlank())
            throw ApiException.badRequest("Informe o paciente da consulta (pacienteId)");
        ObjectId pacienteId = Query.objectId(pacienteIdTexto, "pacienteId");
        if (pacientes.consultar(pacienteId.toHexString()).isEmpty())
            throw ApiException.notFound("Paciente não encontrado");
        return pacienteId;
    }

    // ------------------------------------------------------------------ apoio

    private void validarMedicoEEspecialidade(ObjectId medicoId, ObjectId especialidadeId) {
        Optional<MedicoResponse> medico = medicos.consultar(medicoId);
        if (medico.isEmpty() || !medico.get().ativo())
            throw ApiException.notFound("Médico não encontrado ou inativo");

        Optional<EspecialidadeResponse> especialidade = especialidades.consultar(especialidadeId);
        if (especialidade.isEmpty() || !especialidade.get().ativa())
            throw ApiException.notFound("Especialidade não encontrada ou inativa");

        if (!medico.get().especialidadeIds().contains(especialidadeId.toHexString()))
            throw ApiException.badRequest("O médico não atende esta especialidade");
    }

    private static LocalDate paraData(String valor, String campo) {
        if (valor == null || valor.isBlank()) throw ApiException.badRequest("Parâmetro obrigatório: " + campo);
        try {
            return LocalDate.parse(valor);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(campo + " deve estar no formato AAAA-MM-DD");
        }
    }

    /** Aceita o formato devolvido por GET /vagas (2026-11-16T11:00:00Z) ou com offset (-03:00). */
    private static Instant paraInstante(String valor, String campo) {
        if (valor == null || valor.isBlank()) throw ApiException.badRequest(campo + " é obrigatório");
        try {
            return OffsetDateTime.parse(valor).toInstant();
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(campo + " deve ser data e hora com fuso, ex.: 2026-11-16T11:00:00Z");
        }
    }
}
