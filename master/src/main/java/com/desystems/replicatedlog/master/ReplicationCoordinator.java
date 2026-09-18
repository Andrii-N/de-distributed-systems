package com.desystems.replicatedlog.master;

import com.desystems.replicatedlog.common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Fans a message out to every secondary in parallel and blocks until all of
 * them have acked. This is what makes the master's {@code POST /messages}
 * a blocking replication call.
 */
public final class ReplicationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ReplicationCoordinator.class);

    private final List<SecondaryClient> secondaries;
    private final ExecutorService executor;

    public ReplicationCoordinator(List<SecondaryClient> secondaries, ExecutorService executor) {
        this.secondaries = secondaries;
        this.executor = executor;
    }

    /**
     * Replicates the message to every secondary and waits for all acks before
     * returning. Throws if any secondary fails to ack (perfect-link
     * assumption: this iteration does not retry).
     */
    public void replicateToAll(Message message) {
        long start = System.nanoTime();

        List<CompletableFuture<Void>> acks = secondaries.stream()
                .map(secondary -> CompletableFuture.runAsync(() -> secondary.replicate(message), executor))
                .toList();

        CompletableFuture.allOf(acks.toArray(new CompletableFuture[0])).join();

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("Message id={} acked by all {} secondaries in {}ms", message.getId(), secondaries.size(), elapsedMs);
    }
}
