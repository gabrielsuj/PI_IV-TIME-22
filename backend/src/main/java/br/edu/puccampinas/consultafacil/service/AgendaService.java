package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.AgendaRequest;
import br.edu.puccampinas.consultafacil.api.dto.AgendaResponse;
import br.edu.puccampinas.consultafacil.api.dto.FaixaDto;
import br.edu.puccampinas.consultafacil.api.dto.JornadaDto;
import br.edu.puccampinas.consultafacil.repository.AgendaRepository;
import br.edu.puccampinas.consultafacil.repository.MedicoRepository;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.Comparator;
import java.util.List;

public class AgendaService {
    public static final String FUSO_PADRAO = "America/Sao_Paulo";

    private final AgendaRepository agendas;
    private final MedicoRepository medicos;

    public AgendaService(AgendaRepository agendas, MedicoRepository medicos) {
        this.agendas = agendas;
        this.medicos = medicos;
    }

    public AgendaResponse buscar(ObjectId medicoId) {
        garantirMedico(medicoId);
        return agendas.buscarPorMedico(medicoId)
                .map(AgendaService::paraResposta)
                .orElseThrow(() -> ApiException.notFound("Agenda não configurada para este médico"));
    }

    public AgendaResponse salvar(ObjectId medicoId, AgendaRequest r) {
        garantirMedico(medicoId);
        ValidadorAgenda.validar(r);
        // TODO #44: verificar consultas ativas que ficariam fora da nova jornada antes de gravar

        List<Document> jornadas = r.jornadas().stream()
                .sorted(Comparator.comparing(JornadaDto::diaSemana))
                .map(j -> new Document("diaSemana", j.diaSemana())
                        .append("faixas", j.faixas().stream()
                                .sorted(Comparator.comparing(FaixaDto::inicioMinuto))
                                .map(f -> new Document("inicioMinuto", f.inicioMinuto())
                                        .append("fimMinuto", f.fimMinuto()))
                                .toList()))
                .toList();

        Document dados = new Document("fusoHorario", r.fusoHorario() != null ? r.fusoHorario() : FUSO_PADRAO)
                .append("duracaoMinutos", r.duracaoMinutos())
                .append("jornadas", jornadas);

        return paraResposta(agendas.salvar(medicoId, dados));
    }

    private void garantirMedico(ObjectId id) {
        if (!medicos.existeAtivo(id)) throw ApiException.notFound("Médico não encontrado ou inativo");
    }

    static AgendaResponse paraResposta(Document d) {
        List<JornadaDto> jornadas = d.getList("jornadas", Document.class).stream()
                .map(j -> new JornadaDto(j.getInteger("diaSemana"),
                        j.getList("faixas", Document.class).stream()
                                .map(f -> new FaixaDto(f.getInteger("inicioMinuto"), f.getInteger("fimMinuto")))
                                .toList()))
                .toList();
        return new AgendaResponse(
                d.getObjectId("_id").toHexString(),
                d.getObjectId("medicoId").toHexString(),
                d.getString("fusoHorario"),
                d.getInteger("duracaoMinutos"),
                jornadas);
    }
}
