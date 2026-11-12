package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.ConflitoComConsultasException;
import br.edu.puccampinas.consultafacil.api.dto.AgendaRequest;
import br.edu.puccampinas.consultafacil.api.dto.AgendaResponse;
import br.edu.puccampinas.consultafacil.api.dto.FaixaDto;
import br.edu.puccampinas.consultafacil.api.dto.JornadaDto;
import br.edu.puccampinas.consultafacil.repository.AgendaRepository;
import br.edu.puccampinas.consultafacil.repository.MedicoRepository;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

public class AgendaService {
    public static final String FUSO_PADRAO = "America/Sao_Paulo";

    private final AgendaRepository agendas;
    private final MedicoRepository medicos;
    private final ConsultasAfetadas afetadas;
    private final Clock relogio;

    public AgendaService(AgendaRepository agendas, MedicoRepository medicos, ConsultasAfetadas afetadas, Clock relogio) {
        this.agendas = agendas;
        this.medicos = medicos;
        this.afetadas = afetadas;
        this.relogio = relogio;
    }

    public AgendaResponse buscar(ObjectId medicoId) {
        garantirMedico(medicoId);
        return agendas.buscarPorMedico(medicoId)
                .map(AgendaService::paraResposta)
                .orElseThrow(() -> ApiException.notFound("Agenda não configurada para este médico"));
    }

    public AgendaResponse salvar(ObjectId medicoId, AgendaRequest r) { return salvar(medicoId, r, false); }

    /**
     * Grava a jornada. Se consultas ativas futuras ficarem fora da nova jornada e o usuário ainda
     * não confirmou, recusa com 409 listando as consultas (#44). Confirmado, grava e mantém as
     * consultas, que aparecem como "Remarcar" no calendário (#23).
     */
    public AgendaResponse salvar(ObjectId medicoId, AgendaRequest r, boolean confirmado) {
        garantirMedico(medicoId);
        ValidadorAgenda.validar(r);
        ZoneId fuso = ZoneId.of(r.fusoHorario() != null ? r.fusoHorario() : FUSO_PADRAO);
        if (!confirmado) {
            List<Document> fora = afetadas.futuras(medicoId, relogio.instant()).stream()
                    .filter(c -> !cabeNaJornada(c, r, fuso)).toList();
            if (!fora.isEmpty())
                throw new ConflitoComConsultasException("A nova jornada deixa " + fora.size()
                        + " consulta(s) marcada(s) fora do horário de atendimento. Confirme para salvar mesmo assim; elas precisarão ser remarcadas.",
                        afetadas.resumir(fora, medicoId, fuso));
        }

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

    /** Se a consulta continua inteira dentro de alguma faixa do mesmo dia da semana na jornada informada. */
    static boolean cabeNaJornada(Document consulta, AgendaRequest r, ZoneId fuso) {
        LocalDateTime inicio = LocalDateTime.ofInstant(consulta.getDate("inicio").toInstant(), fuso);
        LocalDateTime fim = LocalDateTime.ofInstant(consulta.getDate("fim").toInstant(), fuso);
        int de = inicio.getHour() * 60 + inicio.getMinute();
        int ate = fim.getHour() * 60 + fim.getMinute() + (fim.toLocalDate().isAfter(inicio.toLocalDate()) ? 1440 : 0);
        int dia = inicio.getDayOfWeek().getValue();
        return r.jornadas().stream()
                .filter(j -> j.diaSemana() == dia)
                .flatMap(j -> j.faixas().stream())
                .anyMatch(f -> de >= f.inicioMinuto() && ate <= f.fimMinuto());
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
