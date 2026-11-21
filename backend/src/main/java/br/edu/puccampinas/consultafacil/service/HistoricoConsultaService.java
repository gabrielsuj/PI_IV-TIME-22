/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.HistoricoConsultaResponse;
import br.edu.puccampinas.consultafacil.api.dto.HistoricoEventoResponse;
import br.edu.puccampinas.consultafacil.repository.HistoricoLeituraRepository;
import br.edu.puccampinas.consultafacil.repository.NomesRepository;
import br.edu.puccampinas.consultafacil.repository.PendenciasConsultaRepository;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Leitura autorizada da #46; não processa pendências nem gera eventos como efeito colateral. */
public final class HistoricoConsultaService {
    public static final int LIMITE_PADRAO = HistoricoLeituraRepository.LIMITE_MAXIMO;
    private final PendenciasConsultaRepository consultas;
    private final HistoricoLeituraRepository historico;
    private final AcessoConsulta acesso;
    private final NomesRepository nomes;

    public HistoricoConsultaService(PendenciasConsultaRepository consultas, HistoricoLeituraRepository historico,
                                    AcessoConsulta acesso, NomesRepository nomes) {
        this.consultas = consultas;
        this.historico = historico;
        this.acesso = acesso;
        this.nomes = nomes;
    }

    public HistoricoConsultaResponse listar(ObjectId consultaId, UsuarioLogado ator) {
        return listar(consultaId, 0, LIMITE_PADRAO, ator);
    }

    public HistoricoConsultaResponse listar(ObjectId consultaId, int aposSequencia, int limite, UsuarioLogado ator) {
        if (ator == null || ator.perfil() == null || ator.id() == null || !ObjectId.isValid(ator.id()))
            throw ApiException.unauthorized("Faça login para consultar o histórico");
        if (consultaId == null) throw ApiException.badRequest("ID da consulta é obrigatório");
        if (aposSequencia < 0) throw ApiException.badRequest("aposSequencia deve ser um inteiro de 0 a 2147483647");
        if (limite < 1 || limite > HistoricoLeituraRepository.LIMITE_MAXIMO)
            throw ApiException.badRequest("limite deve ser um inteiro de 1 a " + HistoricoLeituraRepository.LIMITE_MAXIMO);
        var consulta = consultas.buscar(consultaId).orElseThrow(() -> ApiException.notFound("Consulta não encontrada"));
        // Confere o vínculo antes de buscar eventos e nomes, evitando carregar
        // detalhes de uma consulta que o usuário não pode acessar.
        acesso.verificar(consulta, ator);
        var eventos = historico.listarPorConsulta(consultaId, aposSequencia, limite).stream().map(HistoricoEventoResponse::de).toList();
        boolean limiteAtingido = eventos.size() == limite;
        Integer ultima = eventos.isEmpty() ? null : eventos.getLast().sequencia();
        // Uma leitura adicional de no máximo um evento evita anunciar uma página extra inexistente.
        boolean temMais = limiteAtingido && !historico.listarPorConsulta(consultaId, ultima, 1).isEmpty();
        var ids = eventos.stream().map(HistoricoEventoResponse::ator).filter(Objects::nonNull)
                .filter(atorEvento -> !"SISTEMA".equals(atorEvento.perfil())).map(atorEvento -> atorEvento.id())
                .filter(Objects::nonNull).map(ObjectId::new).collect(Collectors.toSet());
        Map<ObjectId, String> nomesUsuarios = ids.isEmpty() ? Map.of() : nomes.usuarios(ids);
        eventos = eventos.stream().map(evento -> nomeDoAtor(evento, nomesUsuarios)).toList();
        // Uma projeção ou escrita pode terminar entre a busca dos eventos e esta confirmação.
        var atual = consultas.buscar(consultaId).orElseThrow(() -> ApiException.notFound("Consulta não encontrada"));
        acesso.verificar(atual, ator);
        boolean atualizacaoPendente = possuiPendencias(consulta) || possuiPendencias(atual)
                || !Objects.equals(consulta.getInteger("versao", 0), atual.getInteger("versao", 0));
        return new HistoricoConsultaResponse(consultaId.toHexString(), eventos, aposSequencia, limite, limiteAtingido,
                temMais, temMais ? ultima : null, atualizacaoPendente);
    }

    private static boolean possuiPendencias(Document consulta) {
        return !consulta.getList("eventosPendentes", Document.class, List.of()).isEmpty();
    }

    private static HistoricoEventoResponse nomeDoAtor(HistoricoEventoResponse evento, Map<ObjectId, String> nomes) {
        if (evento.ator() == null || evento.ator().id() == null) return evento;
        ObjectId id = new ObjectId(evento.ator().id());
        return nomes.containsKey(id) ? evento.comNomeDoAtor(nomes.get(id)) : evento;
    }
}
