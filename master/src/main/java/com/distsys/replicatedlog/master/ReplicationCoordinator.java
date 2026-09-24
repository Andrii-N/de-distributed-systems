package com.distsys.replicatedlog.master;

import com.distsys.replicatedlog.common.Message;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
/**
 * Send message out to every secondary in parallel and blocks until all of
 * them have acked. This is what makes the master's {@code POST /messages}
 * a blocking replication call.
 */
public final class ReplicationCoordinator {
    
    private final List<SecondaryClient> secondaries;
    private final ExecutorService executor;

    public ReplicationCoordinator(List<SecondaryClient> secondaries, ExecutorService executor) {
        this.secondaries = secondaries;
        this.executor = executor;
    }

    public void replicateToAll(Message message) {
        /* .runAsync() starts each task on another thread and returns CompletableFuture<Void> immediately. */
        List<CompletableFuture<Void>> acks = secondaries.stream()
            .map(secondary -> CompletableFuture.runAsync(() -> secondary.replicate(message), executor))
            .toList();
        
        try {
            /* 
            * allOf(...) returns one future that completes only when every future in the array has completed.
            * .join() blocks the current thread until that happens. This is the line that makes replication blocking: the master won't answer its client until all secondaries have acked.
            */
            CompletableFuture.allOf(acks.toArray(CompletableFuture[]::new)).join();
        }
        catch (CompletionException e) {
            if (e.getCause() instanceof SecondaryClient.ReplicationException replicationException) {
                throw replicationException;
            }
            throw e;
        }
    }

}
