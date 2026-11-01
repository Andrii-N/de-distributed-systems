package com.distsys.replicatedlog.master;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.distsys.replicatedlog.common.HttpSupport;
import com.distsys.replicatedlog.common.Message;
import com.distsys.replicatedlog.common.ReplicationException;
import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HttpTransport against small fake secondaries (HttpServers on OS-assigned ports)
 */
public class HttpTransportTest {

    private static final Message MESSAGE = new Message(1, "hello", 123L);

    private final List<HttpServer> fakeSecondaries = new ArrayList<>();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        fakeSecondaries.forEach(server -> server.stop(0));
        executor.shutdownNow();
    }

    @Test
    void completesNormallyWhenSecondaryAcks() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        int port = startFakeSecondary(0, 200, requests, receivedBody);
        HttpTransport transport = transportTo("secondary1", port);

        transport.send("master", "secondary1", MESSAGE).get(10, TimeUnit.SECONDS);

        assertEquals(1, requests.get());
        assertEquals(MESSAGE, Message.fromJson(JsonParser.parseString(receivedBody.get()).getAsJsonObject()));
    }

    @Test
    void failsWithReplicationExceptionWhenSecondaryAnswersNon200() throws IOException {
        int port = startFakeSecondary(0, 500, new AtomicInteger(), new AtomicReference<>());
        HttpTransport transport = transportTo("secondary1", port);

        assertFailsWithReplicationException(transport.send("master", "secondary1", MESSAGE));
    }

    @Test
    void unknownTargetReturnsFailedFutureInsteadOfThrowing() {
        HttpTransport transport = transportTo("secondary1", 1);

        CompletableFuture<Void> future = assertDoesNotThrow(() -> transport.send("master", "secondary9", MESSAGE));

        assertTrue(future.isCompletedExceptionally());
        assertFailsWithReplicationException(future);
    }

    @Test
    void failsWithReplicationExceptionWhenNothingListens() throws IOException {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        HttpTransport transport = transportTo("secondary1", unusedPort);

        assertFailsWithReplicationException(transport.send("master", "secondary1", MESSAGE));
    }

    /** Transport rule 1: send() hands the request off and returns; only the future waits for the ack */
    @Test
    void sendReturnsBeforeSlowSecondaryAcks() throws Exception {
        long delayMs = 1000;
        int port = startFakeSecondary(delayMs, 200, new AtomicInteger(), new AtomicReference<>());
        HttpTransport transport = transportTo("secondary1", port);

        long start = System.nanoTime();
        CompletableFuture<Void> future = transport.send("master", "secondary1", MESSAGE);
        long sendReturnedMs = (System.nanoTime() - start) / 1_000_000;
        future.get(10, TimeUnit.SECONDS);
        long ackedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(sendReturnedMs < delayMs / 2, "send() should return immediately, took " + sendReturnedMs + "ms");
        assertTrue(ackedMs >= delayMs, "future should complete only after the ack, took " + ackedMs + "ms");
    }

    @Test
    void rejectsEmptySecondaries() {
        assertThrows(IllegalArgumentException.class, () -> new HttpTransport(Map.of(), executor));
    }

    private HttpTransport transportTo(String nodeId, int port) {
        return new HttpTransport(Map.of(nodeId, new SecondaryClient(nodeId, "http://localhost:" + port, httpClient)), executor);
    }

    private static void assertFailsWithReplicationException(CompletableFuture<Void> future) {
        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
        assertInstanceOf(ReplicationException.class, e.getCause());
    }

    private int startFakeSecondary(long delayMs, int statusCode, AtomicInteger requests, AtomicReference<String> receivedBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/replicate", exchange -> {
            receivedBody.set(HttpSupport.readRequestBody(exchange));
            requests.incrementAndGet();
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            HttpSupport.sendPlainText(exchange, statusCode, statusCode == 200 ? "ok" : "simulated failure");
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        fakeSecondaries.add(server);
        return server.getAddress().getPort();
    }
}
