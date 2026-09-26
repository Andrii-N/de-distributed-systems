package com.distsys.replicatedlog.master;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.distsys.replicatedlog.common.MessageStore;
import com.distsys.replicatedlog.common.Message;

/**
 * The master's authoritative log: assigns ids, appends locally and blocks
 * until the message has been replicated to every secondary.
 
 */
public class ReplicatedLog {

    private static final Logger log = LoggerFactory.getLogger(ReplicatedLog.class);

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
        log.info("Appended message id={} to master log, replicating to secondaries", message.getId());
        coordinator.replicateToAll(message);

        return message;
    }

    public String toJsonArray() {
        return messages.toJsonArray();
    }
    
}
