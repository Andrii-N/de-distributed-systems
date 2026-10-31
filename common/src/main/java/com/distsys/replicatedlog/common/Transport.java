package com.distsys.replicatedlog.common;

import java.util.concurrent.CompletableFuture;

/*
 * Link between node logic and the network.
*/
@FunctionalInterface 
public interface Transport {
    /**
     * It returns quickly and never blocks the caller. Delivery happens on another thread.
     * Normal completion means the target acknowledged that it stored the message.
     * Every failure arrives as a future that completed exceptionally with {@link ReplicationException}. Nothing is thrown synchronously from send itself.
     * Each call is one attempt to one node, with no retries.
     * It is safe to call from many threads at once;
     * There is no ordering guarantee between two sends to the same node. Secondaries must order messages themselves
     * @param from Master nodeId 
     * @param to Secondary nodeId
     * @param message {@link Message}
     * @return a future that completes when to acknowledges, or completes exceptionally with ReplicationException    
     */
    CompletableFuture<Void> send(String from, String to, Message message);
}
