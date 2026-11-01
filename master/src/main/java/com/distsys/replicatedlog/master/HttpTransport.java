package com.distsys.replicatedlog.master;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.distsys.replicatedlog.common.Transport;
import com.distsys.replicatedlog.common.Message;
import com.distsys.replicatedlog.common.ReplicationException;

/**
 * Production Transport: replicates over HTTP, one blocking SecondaryClient request per send, run on the given executor.
 */
public final class HttpTransport implements Transport {

    private final Map<String, SecondaryClient> secondaries;
    private final ExecutorService executor;
    
    public HttpTransport(Map<String, SecondaryClient> secondaries, ExecutorService executor) {
        /* Immutable copy of secondaries by node id; Map.copyOf also rejects null keys and values */
        this.secondaries = Map.copyOf(Objects.requireNonNull(secondaries, "secondaries"));
        if (this.secondaries.isEmpty()) {
            throw new IllegalArgumentException("HttpTransport needs at least one secondary");
        }
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Never throws: an unknown target and every replication failure come back as a failed future (see {@link Transport#send})
     */
    @Override
    public CompletableFuture<Void> send(String from, String to, Message message) {
        SecondaryClient secondary = secondaries.get(to);
        if (secondary == null) {
            return CompletableFuture.failedFuture(
                    new ReplicationException("Unknown target node '" + to + "' for message id=" + message.getId() + " from " + from));
        }
        /* Blocking HTTP call runs on the executor; whatever replicate() throws completes the future exceptionally */
        return CompletableFuture.runAsync(() -> secondary.replicate(message), executor);
    }
}
