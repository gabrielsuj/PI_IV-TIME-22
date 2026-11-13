package br.edu.puccampinas.consultafacil.unit;

import br.edu.puccampinas.consultafacil.service.SinalizacaoFilaService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Regra de turno da fila (#32). Horários em UTC; São Paulo = UTC-3. */
class TurnoFilaTest {
    private static boolean atende(String turno, String instanteUtc) {
        return SinalizacaoFilaService.turnoCompativel(turno, Instant.parse(instanteUtc));
    }

    @Test void manhaVaiAteAntesDoMeioDiaEmSaoPaulo() {
        assertTrue(atende("MANHA", "2026-10-26T11:00:00Z"));  // 08:00
        assertTrue(atende("MANHA", "2026-10-26T14:59:00Z"));  // 11:59
        assertFalse(atende("MANHA", "2026-10-26T15:00:00Z")); // 12:00
    }

    @Test void tardeComecaAoMeioDiaEmSaoPaulo() {
        assertTrue(atende("TARDE", "2026-10-26T15:00:00Z"));  // 12:00
        assertTrue(atende("TARDE", "2026-10-26T21:00:00Z"));  // 18:00
        assertFalse(atende("TARDE", "2026-10-26T14:30:00Z")); // 11:30
    }

    @Test void meioDiaEmUtcAindaEManhaEmSaoPaulo() {
        // 12:00Z = 09:00 em São Paulo: comparar a hora em UTC classificaria errado.
        assertTrue(atende("MANHA", "2026-10-26T12:00:00Z"));
        assertFalse(atende("TARDE", "2026-10-26T12:00:00Z"));
    }

    @Test void qualquerAceitaTodosOsHorarios() {
        assertTrue(atende("QUALQUER", "2026-10-26T11:00:00Z"));
        assertTrue(atende("QUALQUER", "2026-10-26T21:00:00Z"));
    }
}
