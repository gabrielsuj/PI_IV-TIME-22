package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.api.dto.ConsultaDetalheResponse;
import br.edu.puccampinas.consultafacil.domain.enums.StatusConsulta;
import br.edu.puccampinas.consultafacil.repository.ConsultaRepository;
import br.edu.puccampinas.consultafacil.repository.NomesRepository;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Listagem e detalhe de consultas (#30), respeitando quem pode ver o quê (AcessoConsulta):
 * paciente → as próprias; médico → as que atende (com os dados básicos do paciente); secretaria → todas.
 */
public class ConsultaLeituraService {
    /** Máximo de consultas por listagem; a tela orienta a usar os filtros quando chega no limite. */
    public static final int LIMITE = 100;

    private final ConsultaRepository consultas;
    private final NomesRepository nomes;
    private final AcessoConsulta acesso;
    private final Clock relogio;

    public ConsultaLeituraService(ConsultaRepository consultas, NomesRepository nomes, AcessoConsulta acesso, Clock relogio) {
        this.consultas = consultas;
        this.nomes = nomes;
        this.acesso = acesso;
        this.relogio = relogio;
    }

    /**
     * @param periodo "proximas" (padrão: ainda não terminaram, da mais próxima para a mais distante)
     *                ou "anteriores" (já terminaram, da mais recente para a mais antiga)
     * @param status  opcional, um dos status da consulta
     * @param medicoId opcional
     */
    public List<ConsultaDetalheResponse> listar(String periodo, String status, String medicoId, UsuarioLogado ator) {
        List<Bson> filtros = new ArrayList<>();
        Bson restricao = acesso.restricao(ator);
        if (restricao != null) filtros.add(restricao);

        Date agora = Date.from(relogio.instant());
        boolean proximas = periodo == null || periodo.isBlank() || "proximas".equals(periodo);
        if (!proximas && !"anteriores".equals(periodo))
            throw ApiException.badRequest("periodo deve ser proximas ou anteriores");
        filtros.add(proximas ? Filters.gt("fim", agora) : Filters.lte("fim", agora));

        if (status != null && !status.isBlank()) filtros.add(Filters.eq("status", statusValido(status)));
        if (medicoId != null && !medicoId.isBlank()) filtros.add(Filters.eq("medicoId", Query.objectId(medicoId, "medicoId")));

        return resumir(consultas.listar(Filters.and(filtros), proximas, LIMITE));
    }

    public ConsultaDetalheResponse detalhar(ObjectId id, UsuarioLogado ator) {
        Document consulta = consultas.buscar(id).orElseThrow(() -> ApiException.notFound("Consulta não encontrada"));
        acesso.verificar(consulta, ator);
        return resumir(List.of(consulta)).get(0);
    }

    private static String statusValido(String status) {
        try {
            return StatusConsulta.valueOf(status).name();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("status deve ser um de " + Arrays.toString(StatusConsulta.values()));
        }
    }

    /** Nomes buscados em lote (uma consulta por coleção), sem N+1. */
    private List<ConsultaDetalheResponse> resumir(List<Document> docs) {
        Map<ObjectId, String> pacientes = nomes.pacientes(ids(docs, "pacienteId"));
        Map<ObjectId, String> medicos = nomes.medicos(ids(docs, "medicoId"));
        Map<ObjectId, String> especialidades = nomes.especialidades(ids(docs, "especialidadeId"));
        Instant agora = relogio.instant();
        return docs.stream().map(d -> new ConsultaDetalheResponse(
                d.getObjectId("_id").toHexString(),
                d.getObjectId("pacienteId").toHexString(), pacientes.get(d.getObjectId("pacienteId")),
                d.getObjectId("medicoId").toHexString(), medicos.get(d.getObjectId("medicoId")),
                d.getObjectId("especialidadeId").toHexString(), especialidades.get(d.getObjectId("especialidadeId")),
                d.getDate("inicio").toInstant().toString(),
                d.getDate("fim").toInstant().toString(),
                d.getString("status"),
                d.getString("motivoCancelamento"),
                CancelamentoService.podeCancelar(d, agora))).toList();
    }

    private static Set<ObjectId> ids(List<Document> docs, String campo) {
        return docs.stream().map(d -> d.getObjectId(campo)).collect(Collectors.toSet());
    }
}
