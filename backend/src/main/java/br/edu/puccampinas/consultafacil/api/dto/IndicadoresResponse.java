/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

package br.edu.puccampinas.consultafacil.api.dto;

import br.edu.puccampinas.consultafacil.api.JsonComNulos;
import br.edu.puccampinas.consultafacil.service.CalculadoraIndicadores;
import com.google.gson.annotations.JsonAdapter;

import java.math.BigDecimal;

@JsonAdapter(JsonComNulos.class)
public record IndicadoresResponse(String de, String ate, String fusoHorario, String medicoId,
                                  Contagens contagens, Taxa ocupacao, Taxa faltas, Taxa cancelamento,
                                  Taxa confirmacao, Taxa preenchimentoFila, Qualidade qualidade) {
    public enum Situacao { DISPONIVEL, SEM_BASE, DADOS_PENDENTES, DADOS_INCOMPLETOS }

    @JsonAdapter(JsonComNulos.class)
    public record Taxa(long numerador, long denominador, BigDecimal percentual, Situacao situacao) {
        public static Taxa de(CalculadoraIndicadores.Taxa taxa) {
            return new Taxa(taxa.numerador(), taxa.denominador(), taxa.percentual(),
                    taxa.denominador() == 0 ? Situacao.SEM_BASE : Situacao.DISPONIVEL);
        }

        public static Taxa indefinida(CalculadoraIndicadores.Taxa taxa, Situacao motivo) {
            if (motivo != Situacao.DADOS_PENDENTES && motivo != Situacao.DADOS_INCOMPLETOS)
                throw new IllegalArgumentException("Informe o motivo da taxa indefinida");
            return new Taxa(taxa.numerador(), taxa.denominador(), null, motivo);
        }
    }

    public record Contagens(long totalConsultas, long consultasNaoCanceladas, long consultasComDesfecho,
                            long capacidade, long vagasOcupadas, long agendadas, long confirmadas,
                            long concluidas, long faltas, long canceladas,
                            long comConfirmacaoRegistrada, long preenchidasPelaFila) {
        public static Contagens de(CalculadoraIndicadores.Contagens base) {
            return new Contagens(base.totalConsultas(), base.consultasNaoCanceladas(), base.consultasComDesfecho(),
                    base.capacidade(), base.vagasOcupadas(), base.agendadas(), base.confirmadas(), base.concluidas(),
                    base.faltas(), base.canceladas(), base.comConfirmacaoRegistrada(), base.preenchidasPelaFila());
        }
    }

    // As contagens de qualidade acompanham as taxas para explicar por que um
    // percentual está ausente sem apresentar um zero artificial.
    public record Qualidade(long medicosSemAgenda, long consultasComProjecaoPendente,
                            long consultasSemBaseHistorica, long inscricoesComVinculoInvalido,
                            boolean capacidadeEstimada, String observacaoCapacidade) {}
}
