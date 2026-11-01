package com.distsys.replicatedlog.master;

import com.distsys.replicatedlog.common.Message;
import com.distsys.replicatedlog.common.ReplicationException;
import com.distsys.replicatedlog.common.Transport;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * Send message out to every secondary in parallel and blocks until all of
 * them have acked. This is what makes the master's {@code POST /messages}
 * a blocking replication call.
 */
public final class ReplicationCoordinator {
    
    private static final Logger log = LoggerFactory.getLogger(ReplicationCoordinator.class);

    private final Transport transport;
    private final String fromId;
    private final List<String> secondaryIds;
    
    public ReplicationCoordinator(Transport transport, String fromId, List<String> secondaryIds) {
        this.transport = transport;
        this.fromId = Objects.requireNonNull(fromId);
        this.secondaryIds = List.copyOf(secondaryIds);
        if (this.secondaryIds.isEmpty()) {
            throw new IllegalArgumentException("HttpTransport needs at least one secondary");
        }
    }

    public void replicateToAll(Message message) {

        long start = System.nanoTime();

        /* `send` returns immediately, so all secondaries are contacted in parallel */
        List<CompletableFuture<Void>> acks = secondaryIds.stream()
            .map(secondaryId -> transport.send(fromId, secondaryId, message))
            .toList();
        
        try {
            /* 
            * allOf(...) returns one future that completes only when every future in the array has completed.
            * .join() blocks the current thread until that happens. This is the line that makes replication blocking: the master won't answer its client until all secondaries have acked.
            */
            CompletableFuture.allOf(acks.toArray(CompletableFuture[]::new)).join();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.info("Message id={} acked by all {} secondaries in {} milliseconds", message.getId(), secondaryIds.size(), elapsedMs);
        }
        catch (CompletionException e) {
            if (e.getCause() instanceof ReplicationException replicationException) {
                throw replicationException;
            }
            throw e;
        }
    }

}
