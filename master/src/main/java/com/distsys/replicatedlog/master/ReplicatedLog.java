package com.distsys.replicatedlog.master;

import java.util.concurrent.atomic.AtomicLong;

import com.distsys.replicatedlog.common.MessageStore;
import com.distsys.replicatedlog.common.Message;

/**
 * The master's authoritative log: assigns ids, appends locally and blocks
 * until the message has been replicated to every secondary.
 
 */
public class ReplicatedLog {
    private final MessageStore messages = new MessageStore();
    /* Thread-safe auto-increment id */
    private final AtomicLong nextId = new AtomicLong(1);
    private final ReplicationCoordinator coordinator;

    public ReplicatedLog(ReplicationCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public synchronized Message appendAndReplicate (String bodyText) {
        Message message = new Message(String.valueOf(nextId.getAndIncrement()), bodyText,  System.currentTimeMillis());

        messages.append(message);

        coordinator.replicateToAll(message);

        return message;
    }

    public String toJsonArray() {
        return messages.toJsonArray();
    }
    
}
