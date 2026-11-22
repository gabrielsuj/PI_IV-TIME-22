package br.edu.puccampinas.consultafacil.protocol;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ParceiroTest {
    @Test(timeout = 10000)
    public void observacaoEConsumoNaoLeemOStreamSimultaneamente() throws Exception {
        CountDownLatch lendo = new CountDownLatch(1), liberar = new CountDownLatch(1), consumir = new CountDownLatch(1);
        AtomicInteger leituras = new AtomicInteger(), ativas = new AtomicInteger(), maximo = new AtomicInteger();
        ObjectInputStream entrada = new ObjectInputStream() {
            @Override protected Object readObjectOverride() throws IOException {
                int ordem = leituras.incrementAndGet();
                maximo.accumulateAndGet(ativas.incrementAndGet(), Math::max);
                try {
                    if (ordem == 1) { lendo.countDown(); aguardar(liberar); }
                    return new Comunicado();
                } finally {
                    ativas.decrementAndGet();
                }
            }
        };
        Parceiro parceiro = parceiro(entrada);
        ExecutorService executor = executor();
        try {
            Future<Comunicado> observado = executor.submit(parceiro::espie);
            assertTrue(lendo.await(3, TimeUnit.SECONDS));
            Future<Comunicado> recebido = executor.submit(() -> { consumir.countDown(); return parceiro.envie(); });
            assertTrue(consumir.await(3, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> recebido.get(100, TimeUnit.MILLISECONDS));
            liberar.countDown();
            assertSame(observado.get(3, TimeUnit.SECONDS), recebido.get(3, TimeUnit.SECONDS));
            assertEquals(1, maximo.get());
            assertEquals(1, leituras.get());
        } finally {
            liberar.countDown();
            parceiro.adeus();
            executor.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void liberaSemaforoQuandoEspieOuEnvieFalham() throws Exception {
        for (boolean observar : new boolean[]{true, false}) {
            Parceiro parceiro = parceiro(new ObjectInputStream() {
                @Override protected Object readObjectOverride() throws IOException { throw new EOFException(); }
            });
            ExecutorService executor = executor();
            try {
                if (observar) assertThrows(Exception.class, parceiro::espie);
                else assertThrows(Exception.class, parceiro::envie);
                Future<Exception> seguinte = executor.submit(() -> observar
                        ? assertThrows(Exception.class, parceiro::envie)
                        : assertThrows(Exception.class, parceiro::espie));
                assertTrue(seguinte.get(3, TimeUnit.SECONDS).getCause() instanceof EOFException);
            } finally {
                parceiro.adeus();
                executor.shutdownNow();
            }
        }
    }

    @Test(timeout = 10000)
    public void espieMantemCacheEEnvieConsomeUmaVez() throws Exception {
        AtomicInteger leituras = new AtomicInteger();
        Parceiro parceiro = parceiro(new ObjectInputStream() {
            @Override protected Object readObjectOverride() { leituras.incrementAndGet(); return new Comunicado(); }
        });
        try {
            Comunicado mensagem = parceiro.espie();
            assertSame(mensagem, parceiro.espie());
            assertSame(mensagem, parceiro.envie());
            assertEquals(1, leituras.get());
            Comunicado seguinte = parceiro.espie();
            assertNotSame(mensagem, seguinte);
            assertSame(seguinte, parceiro.envie());
            assertEquals(2, leituras.get());
        } finally {
            parceiro.adeus();
        }
    }

    private static void aguardar(CountDownLatch sinal) throws IOException {
        try {
            if (!sinal.await(3, TimeUnit.SECONDS)) throw new IOException("Sinal nao recebido");
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
            throw new IOException(erro);
        }
    }

    private static Parceiro parceiro(ObjectInputStream entrada) throws Exception {
        return new Parceiro(new Socket(), entrada, new ObjectOutputStream(new ByteArrayOutputStream()));
    }

    private static ExecutorService executor() {
        return Executors.newFixedThreadPool(2, tarefa -> {
            Thread thread = new Thread(tarefa);
            thread.setDaemon(true);
            return thread;
        });
    }
}
