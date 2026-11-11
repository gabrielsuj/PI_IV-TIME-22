package br.edu.puccampinas.consultafacil.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Relógio que os testes avançam manualmente, para simular expiração sem esperar. */
public class RelogioAjustavel extends Clock {
    private Instant agora;

    public RelogioAjustavel(Instant inicio) { this.agora = inicio; }

    public void avancar(Duration d) { agora = agora.plus(d); }

    @Override public Instant instant() { return agora; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
}
