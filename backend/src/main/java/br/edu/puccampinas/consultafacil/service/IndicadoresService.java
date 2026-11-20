package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.IndicadoresResponse;
import br.edu.puccampinas.consultafacil.api.dto.IndicadoresResponse.Situacao;
import br.edu.puccampinas.consultafacil.api.dto.IndicadoresResponse.Taxa;
import br.edu.puccampinas.consultafacil.repository.IndicadoresLeituraRepository;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import org.bson.types.ObjectId;

import java.util.Objects;
import java.util.Optional;

public final class IndicadoresService {
    private final IndicadoresLeituraRepository leitura;
    private final AcessoAgenda acesso;

    public IndicadoresService(IndicadoresLeituraRepository leitura, AcessoAgenda acesso) {
        this.leitura = Objects.requireNonNull(leitura);
        this.acesso = Objects.requireNonNull(acesso);
    }

    public IndicadoresResponse consultar(String de, String ate, String solicitado, UsuarioLogado ator) {
        if (ator == null || ator.perfil() == null || ator.id() == null || !ObjectId.isValid(ator.id()))
            throw ApiException.unauthorized("Faça login para consultar os indicadores");
        Optional<ObjectId> medico = solicitado == null ? acesso.restricao(ator)
                : Optional.of(acesso.medicoAutorizado(ator, solicitado));
        PeriodoIndicadores periodo = PeriodoIndicadores.de(de, ate);
        var resumo = Objects.requireNonNull(leitura.ler(periodo, medico), "Fonte dos indicadores não retornou um resumo");
        var consultas = resumo.consultas();
        var capacidade = resumo.capacidade();
        var confirmacao = resumo.confirmacao();
        var fila = resumo.fila();
        var base = new CalculadoraIndicadores.Contagens(capacidade.total(), capacidade.ocupadas(),
                consultas.agendadas(), consultas.confirmadas(), consultas.concluidas(), consultas.faltas(), consultas.canceladas(),
                confirmacao.registradas(), fila.preenchidas());
        if (capacidade.medicosSemAgenda() < 0 || fila.inscricoesComVinculoInvalido() < 0
                || confirmacao.comProjecaoPendente() < 0 || confirmacao.comProjecaoPendente() > base.totalConsultas()
                || confirmacao.semBaseHistorica() < 0 || confirmacao.semBaseHistorica() > base.totalConsultas())
            throw new IllegalStateException("Contagens de qualidade dos indicadores inconsistentes");
        var calculados = CalculadoraIndicadores.calcular(periodo, base);
        Taxa ocupacao = capacidade.medicosSemAgenda() > 0
                ? Taxa.indefinida(calculados.ocupacao(), Situacao.DADOS_INCOMPLETOS) : Taxa.de(calculados.ocupacao());
        Taxa taxaConfirmacao = confirmacao.comProjecaoPendente() > 0
                ? Taxa.indefinida(calculados.confirmacao(), Situacao.DADOS_PENDENTES)
                : confirmacao.semBaseHistorica() > 0
                    ? Taxa.indefinida(calculados.confirmacao(), Situacao.DADOS_INCOMPLETOS) : Taxa.de(calculados.confirmacao());
        Taxa preenchimento = fila.inscricoesComVinculoInvalido() > 0
                ? Taxa.indefinida(calculados.preenchimentoFila(), Situacao.DADOS_INCOMPLETOS) : Taxa.de(calculados.preenchimentoFila());
        return new IndicadoresResponse(de, ate, PeriodoIndicadores.FUSO.getId(), medico.map(ObjectId::toHexString).orElse(null),
                IndicadoresResponse.Contagens.de(base), ocupacao, Taxa.de(calculados.faltas()), Taxa.de(calculados.cancelamento()),
                taxaConfirmacao, preenchimento, new IndicadoresResponse.Qualidade(capacidade.medicosSemAgenda(),
                confirmacao.comProjecaoPendente(), confirmacao.semBaseHistorica(), fila.inscricoesComVinculoInvalido(), true,
                "Capacidade calculada pela agenda atual; alterações históricas de jornada, duração e bloqueios não são reconstruídas."));
    }
}
