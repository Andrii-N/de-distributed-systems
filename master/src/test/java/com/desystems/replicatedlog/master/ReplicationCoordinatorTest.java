package com.desystems.replicatedlog.master;

import com.desystems.replicatedlog.common.HttpSupport;
import com.desystems.replicatedlog.common.Message;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplicationCoordinatorTest {

    private final List<HttpServer> fakeSecondaries = new ArrayList<>();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        fakeSecondaries.forEach(server -> server.stop(0));
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void blocksUntilEverySecondaryHasAcked() throws IOException {
        AtomicInteger fastAcks = new AtomicInteger();
        AtomicInteger slowAcks = new AtomicInteger();

        int fastPort = startFakeSecondary(0, fastAcks);
        long slowDelayMs = 300;
        int slowPort = startFakeSecondary(slowDelayMs, slowAcks);

        executor = Executors.newVirtualThreadPerTaskExecutor();
        List<SecondaryClient> secondaries = List.of(
                new SecondaryClient("fast", "http://localhost:" + fastPort, httpClient),
                new SecondaryClient("slow", "http://localhost:" + slowPort, httpClient));

        ReplicationCoordinator coordinator = new ReplicationCoordinator(secondaries, executor);

        long start = System.currentTimeMillis();
        coordinator.replicateToAll(new Message(1L, "hello", 123L));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(1, fastAcks.get());
        assertEquals(1, slowAcks.get());
        assertTrue(elapsed >= slowDelayMs, "expected coordinator to block for at least " + slowDelayMs + "ms, took " + elapsed + "ms");
    }

    private int startFakeSecondary(long delayMs, AtomicInteger ackCounter) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/replicate", exchange -> {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String body = HttpSupport.readBody(exchange);
            ackCounter.incrementAndGet();
            HttpSupport.sendJson(exchange, 200, body);
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        fakeSecondaries.add(server);
        return server.getAddress().getPort();
    }
}
