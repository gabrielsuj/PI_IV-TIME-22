package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.dto.ConsultaCalendarioResponse;
import br.edu.puccampinas.consultafacil.repository.ConsultaRepository;
import br.edu.puccampinas.consultafacil.repository.NomesRepository;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Consultas ativas atingidas por uma mudança na agenda do médico (#44), já com nomes e
 * horário local, no mesmo formato do calendário (#23).
 */
public class ConsultasAfetadas {
    private final ConsultaRepository consultas;
    private final NomesRepository nomes;

    public ConsultasAfetadas(ConsultaRepository consultas, NomesRepository nomes) {
        this.consultas = consultas;
        this.nomes = nomes;
    }

    /** Ativas que tocam o período [inicio, fim). */
    public List<ConsultaCalendarioResponse> noPeriodo(ObjectId medicoId, Instant inicio, Instant fim, ZoneId fuso) {
        return resumir(consultas.ativasNoPeriodo(medicoId, Date.from(inicio), Date.from(fim)), medicoId, fuso);
    }

    /** Ativas que começam a partir de agora (documentos brutos, para checar contra uma nova jornada). */
    public List<Document> futuras(ObjectId medicoId, Instant agora) {
        return consultas.ativasAPartirDe(medicoId, Date.from(agora));
    }

    public List<ConsultaCalendarioResponse> resumir(List<Document> docs, ObjectId medicoId, ZoneId fuso) {
        Map<ObjectId, String> pacientes = nomes.pacientes(ids(docs, "pacienteId"));
        Map<ObjectId, String> especialidades = nomes.especialidades(ids(docs, "especialidadeId"));
        return docs.stream().map(d -> new ConsultaCalendarioResponse(
                d.getObjectId("_id").toHexString(),
                medicoId.toHexString(),
                d.getObjectId("pacienteId").toHexString(),
                pacientes.get(d.getObjectId("pacienteId")),
                d.getObjectId("especialidadeId").toHexString(),
                especialidades.get(d.getObjectId("especialidadeId")),
                LocalDateTime.ofInstant(d.getDate("inicio").toInstant(), fuso).toString(),
                LocalDateTime.ofInstant(d.getDate("fim").toInstant(), fuso).toString(),
                d.getString("status"))).toList();
    }

    private static Set<ObjectId> ids(List<Document> docs, String campo) {
        return docs.stream().map(d -> d.getObjectId(campo)).collect(Collectors.toSet());
    }
}
