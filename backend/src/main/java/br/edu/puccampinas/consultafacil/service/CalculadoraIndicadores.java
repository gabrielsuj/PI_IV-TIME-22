package br.edu.puccampinas.consultafacil.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public final class CalculadoraIndicadores {
    private CalculadoraIndicadores() {}

    public record Contagens(long capacidade, long vagasOcupadas,
                            long agendadas, long confirmadas, long concluidas, long faltas, long canceladas,
                            long comConfirmacaoRegistrada, long preenchidasPelaFila) {
        public Contagens {
            for (long valor : new long[]{capacidade, vagasOcupadas, agendadas, confirmadas, concluidas,
                    faltas, canceladas, comConfirmacaoRegistrada, preenchidasPelaFila})
                if (valor < 0) throw new IllegalArgumentException("Contagens dos indicadores não podem ser negativas");
            long total = somar(agendadas, confirmadas, concluidas, faltas, canceladas);
            if (vagasOcupadas > capacidade)
                throw new IllegalArgumentException("Vagas ocupadas não podem exceder a capacidade");
            if (comConfirmacaoRegistrada > total)
                throw new IllegalArgumentException("Confirmações distintas não podem exceder o total de consultas");
            if (preenchidasPelaFila > total - canceladas)
                throw new IllegalArgumentException("Reservas mantidas pela fila não podem exceder as consultas não canceladas");
        }

        public long totalConsultas() { return somar(agendadas, confirmadas, concluidas, faltas, canceladas); }
        public long consultasNaoCanceladas() { return totalConsultas() - canceladas; }
        public long consultasComDesfecho() { return somar(concluidas, faltas); }
    }

    public record Taxa(long numerador, long denominador) {
        public Taxa {
            if (numerador < 0 || denominador < 0 || numerador > denominador)
                throw new IllegalArgumentException("Taxa exige 0 <= numerador <= denominador");
        }

        public BigDecimal percentual() {
            return denominador == 0 ? null : BigDecimal.valueOf(numerador).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(denominador), 2, RoundingMode.HALF_UP);
        }
    }

    public record Resultado(PeriodoIndicadores periodo, Contagens contagens, Taxa ocupacao,
                            Taxa faltas, Taxa cancelamento, Taxa confirmacao, Taxa preenchimentoFila) {}

    public static Resultado calcular(PeriodoIndicadores periodo, Contagens base) {
        Objects.requireNonNull(periodo, "Período dos indicadores é obrigatório");
        Objects.requireNonNull(base, "Contagens completas são obrigatórias");
        return new Resultado(periodo, base,
                new Taxa(base.vagasOcupadas(), base.capacidade()),
                new Taxa(base.faltas(), base.consultasComDesfecho()),
                new Taxa(base.canceladas(), base.totalConsultas()),
                new Taxa(base.comConfirmacaoRegistrada(), base.totalConsultas()),
                new Taxa(base.preenchidasPelaFila(), base.consultasNaoCanceladas()));
    }

    private static long somar(long... valores) {
        long total = 0;
        try {
            for (long valor : valores) total = Math.addExact(total, valor);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Contagem dos indicadores excede o limite suportado", e);
        }
        return total;
    }
}
