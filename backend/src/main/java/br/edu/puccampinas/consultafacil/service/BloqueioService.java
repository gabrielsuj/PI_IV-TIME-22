package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.api.dto.BloqueioRequest;
import br.edu.puccampinas.consultafacil.api.dto.BloqueioResponse;
import br.edu.puccampinas.consultafacil.repository.AgendaRepository;
import br.edu.puccampinas.consultafacil.repository.BloqueioRepository;
import br.edu.puccampinas.consultafacil.repository.MedicoRepository;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;

public class BloqueioService {
    private final BloqueioRepository bloqueios;
    private final AgendaRepository agendas;
    private final MedicoRepository medicos;

    public BloqueioService(BloqueioRepository bloqueios, AgendaRepository agendas, MedicoRepository medicos) {
        this.bloqueios = bloqueios;
        this.agendas = agendas;
        this.medicos = medicos;
    }

    public BloqueioResponse criar(BloqueioRequest r) {
        ObjectId medicoId = Query.objectId(r.medicoId(), "medicoId");
        if (!medicos.existeAtivo(medicoId)) throw ApiException.notFound("Médico não encontrado ou inativo");

        ZoneId fuso = fusoDo(medicoId);
        Instant inicio = paraInstante(r.inicio(), "inicio", fuso);
        Instant fim = paraInstante(r.fim(), "fim", fuso);
        if (!inicio.isBefore(fim)) throw ApiException.badRequest("O início do bloqueio deve ser anterior ao fim");
        if (r.motivo() != null && r.motivo().length() > 200) throw ApiException.badRequest("Motivo muito longo (máx. 200)");

        if (!bloqueios.sobrepostos(medicoId, Date.from(inicio), Date.from(fim)).isEmpty())
            throw ApiException.conflict("Já existe um bloqueio nesse período");
        // TODO #44: listar consultas ativas dentro do período e recusar ou exigir confirmação

        Document d = new Document("medicoId", medicoId)
                .append("inicio", Date.from(inicio))
                .append("fim", Date.from(fim));
        if (r.motivo() != null && !r.motivo().isBlank()) d.append("motivo", r.motivo().strip());

        return paraResposta(bloqueios.inserir(d), fuso);
    }

    /** Sem de/ate lista todos; com de/ate (AAAA-MM-DD, inclusivos) lista os que tocam o período. */
    public List<BloqueioResponse> listar(ObjectId medicoId, String de, String ate) {
        ZoneId fuso = fusoDo(medicoId);
        List<Document> docs;
        if (de == null || ate == null) {
            docs = bloqueios.doMedico(medicoId);
        } else {
            Instant ini = paraData(de, "de").atStartOfDay(fuso).toInstant();
            Instant fim = paraData(ate, "ate").plusDays(1).atStartOfDay(fuso).toInstant();
            docs = bloqueios.sobrepostos(medicoId, Date.from(ini), Date.from(fim));
        }
        return docs.stream().map(d -> paraResposta(d, fuso)).toList();
    }

    public void remover(ObjectId id) {
        if (!bloqueios.remover(id)) throw ApiException.notFound("Bloqueio não encontrado");
    }

    private ZoneId fusoDo(ObjectId medicoId) {
        return agendas.buscarPorMedico(medicoId)
                .map(a -> ZoneId.of(a.getString("fusoHorario")))
                .orElse(ZoneId.of(AgendaService.FUSO_PADRAO));
    }

    private static Instant paraInstante(String valor, String campo, ZoneId fuso) {
        if (valor == null) throw ApiException.badRequest(campo + " é obrigatório");
        try {
            return LocalDateTime.parse(valor).atZone(fuso).toInstant();
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(campo + " deve estar no formato AAAA-MM-DDTHH:MM");
        }
    }

    private static LocalDate paraData(String valor, String campo) {
        try {
            return LocalDate.parse(valor);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(campo + " deve estar no formato AAAA-MM-DD");
        }
    }

    private static BloqueioResponse paraResposta(Document d, ZoneId fuso) {
        return new BloqueioResponse(
                d.getObjectId("_id").toHexString(),
                d.getObjectId("medicoId").toHexString(),
                LocalDateTime.ofInstant(d.getDate("inicio").toInstant(), fuso).toString(),
                LocalDateTime.ofInstant(d.getDate("fim").toInstant(), fuso).toString(),
                d.getString("motivo"));
    }
}
