package br.edu.puccampinas.consultafacil.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Contrato e cálculo puro dos indicadores da #36. Recebe contagens completas, do mesmo período
 * e escopo de médicos, sem acesso ao banco, relógio, fila ou processador de histórico.
 * As fontes são consolidadas pelo repositório de indicadores; dado desconhecido não é zero.
 *
 * <p>Decisões do MVP (todas as consultas são distintas por consultaId):</p>
 * <ul>
 *   <li>Ocupação = vagas ocupadas / capacidade. Capacidade são os slots completos da jornada
 *       atual, por duração e descontando bloqueios, sem retirar passado ou consultas. Um slot
 *       está ocupado se sobreposto por alguma consulta não CANCELADA, inclusive CONCLUIDA/FALTA.
 *       Cada slot conta uma vez; ocupação mede reserva da agenda, não comparecimento.</li>
 *   <li>Faltas = FALTA / (CONCLUIDA + FALTA). Consultas ainda em aberto ou canceladas não têm
 *       desfecho de comparecimento e não entram nesse denominador.</li>
 *   <li>Cancelamento = CANCELADA / total de consultas. Os cinco status atuais formam o total.</li>
 *   <li>Confirmação = consultas com evento CONFIRMACAO / total de consultas. Considera qualquer
 *       data do evento para as consultas iniciadas no período, mesmo se depois concluídas ou
 *       canceladas. Repetições do evento não aumentam o numerador; status atual não prova evento.</li>
 *   <li>Fila = consultas não canceladas vinculadas por consultaAgendadaId a inscrições ATENDIDA /
 *       consultas não canceladas. Deduplicar vínculos pela consulta, filtrar seu médico/início,
 *       não atendidaEm. Uma reserva depois cancelada sai de ambos; sinalização/notificação
 *       não prova preenchimento. O percentual mede a origem das reservas mantidas.</li>
 * </ul>
 * <p>Todos os recortes de consultas usam seu início no {@link PeriodoIndicadores}; a ocupação
 * considera os slots iniciados nessa janela e consultas sobrepostas a eles, inclusive as iniciadas
 * antes dela. A agenda atual não reconstrói a capacidade histórica depois de alterações de jornada,
 * duração ou bloqueios. Essa limitação deve acompanhar o resultado na futura resposta da API.</p>
 * <p>Percentuais = 100 * numerador / denominador, duas casas, HALF_UP. Sem denominador, percentual
 * é null (não 0%). Valores inválidos/overflow são recusados, sem limitar silenciosamente a 100%.
 * As contagens de apoio permanecem disponíveis mesmo quando a taxa não tem base de cálculo.</p>
 */
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

        /** null significa ausência de base, enquanto 0.00 significa base existente sem ocorrências. */
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
