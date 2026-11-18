package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.HistoricoConsultaResponse;
import br.edu.puccampinas.consultafacil.api.dto.HistoricoEventoResponse;
import br.edu.puccampinas.consultafacil.repository.HistoricoLeituraRepository;
import br.edu.puccampinas.consultafacil.repository.PendenciasConsultaRepository;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import org.bson.types.ObjectId;

/** Leitura autorizada da #46; não processa pendências nem gera eventos como efeito colateral. */
public final class HistoricoConsultaService {
    private final PendenciasConsultaRepository consultas;
    private final HistoricoLeituraRepository historico;
    private final AcessoConsulta acesso;

    public HistoricoConsultaService(PendenciasConsultaRepository consultas, HistoricoLeituraRepository historico,
                                    AcessoConsulta acesso) {
        this.consultas = consultas;
        this.historico = historico;
        this.acesso = acesso;
    }

    public HistoricoConsultaResponse listar(ObjectId consultaId, UsuarioLogado ator) {
        if (ator == null || ator.perfil() == null || ator.id() == null || !ObjectId.isValid(ator.id()))
            throw ApiException.unauthorized("Faça login para consultar o histórico");
        if (consultaId == null) throw ApiException.badRequest("ID da consulta é obrigatório");
        var consulta = consultas.buscar(consultaId).orElseThrow(() -> ApiException.notFound("Consulta não encontrada"));
        acesso.verificar(consulta, ator);
        int limite = HistoricoLeituraRepository.LIMITE_MAXIMO;
        var eventos = historico.listarPorConsulta(consultaId, 0, limite).stream().map(HistoricoEventoResponse::de).toList();
        return new HistoricoConsultaResponse(consultaId.toHexString(), eventos, limite, eventos.size() == limite);
    }
}
