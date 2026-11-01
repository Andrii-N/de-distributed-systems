package com.distsys.replicatedlog.master;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.distsys.replicatedlog.common.Message;
import com.distsys.replicatedlog.common.ReplicationException;
import com.distsys.replicatedlog.common.Transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coordinator logic only: transports are lambdas, so there is no HTTP and no ports.
 * HTTP behaviour is covered by {@link HttpTransportTest}.
 */
public class ReplicationCoordinatorTest {

    private static final Message MESSAGE = new Message(1, "hello", 123L);

    @Test
    void blocksUntilEverySecondaryHasAcked() {
        long slowDelayMs = 500;
        Transport transport = (from, to, message) -> switch (to) {
            case "fast" -> CompletableFuture.completedFuture(null);
            case "slow" -> CompletableFuture.runAsync(() -> { },
                    CompletableFuture.delayedExecutor(slowDelayMs, TimeUnit.MILLISECONDS));
            default -> CompletableFuture.failedFuture(new ReplicationException("unexpected target " + to));
        };
        ReplicationCoordinator coordinator = new ReplicationCoordinator(transport, "master", List.of("fast", "slow"));

        long start = System.nanoTime();
        coordinator.replicateToAll(MESSAGE);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs >= slowDelayMs, "expected coordinator to block for at least " + slowDelayMs + "ms, took " + elapsedMs + "ms");
    }

    @Test
    void sendsTheMessageToEverySecondaryFromTheConfiguredNode() {
        Set<String> sends = ConcurrentHashMap.newKeySet();
        Transport transport = (from, to, message) -> {
            sends.add(from + "->" + to + ":" + message.getId());
            return CompletableFuture.completedFuture(null);
        };
        ReplicationCoordinator coordinator = new ReplicationCoordinator(transport, "master", List.of("secondary1", "secondary2"));

        coordinator.replicateToAll(MESSAGE);

        assertEquals(Set.of("master->secondary1:1", "master->secondary2:1"), sends);
    }

    @Test
    void throwsReplicationExceptionWhenAnySecondaryFails() {
        Transport transport = (from, to, message) -> to.equals("failing")
                ? CompletableFuture.failedFuture(new ReplicationException("simulated failure"))
                : CompletableFuture.completedFuture(null);
        ReplicationCoordinator coordinator = new ReplicationCoordinator(transport, "master", List.of("healthy", "failing"));

        assertThrows(ReplicationException.class, () -> coordinator.replicateToAll(MESSAGE));
    }

    /**
     * HttpTransport fails inside runAsync, which stores the exception wrapped in a CompletionException
     * (unlike failedFuture above); the coordinator must still surface the ReplicationException itself
     */
    @Test
    void unwrapsReplicationExceptionThrownInsideAnAsyncSend() {
        Transport transport = (from, to, message) -> CompletableFuture.runAsync(() -> {
            throw new ReplicationException("simulated failure in " + to);
        });
        ReplicationCoordinator coordinator = new ReplicationCoordinator(transport, "master", List.of("secondary1"));

        assertThrows(ReplicationException.class, () -> coordinator.replicateToAll(MESSAGE));
    }

    @Test
    void rejectsEmptySecondaryList() {
        Transport transport = (from, to, message) -> CompletableFuture.completedFuture(null);

        assertThrows(IllegalArgumentException.class, () -> new ReplicationCoordinator(transport, "master", List.of()));
    }
}
