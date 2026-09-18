package com.desystems.replicatedlog.master;

import com.desystems.replicatedlog.common.Message;
import com.desystems.replicatedlog.common.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The master's authoritative log: assigns ids, appends locally and blocks
 * until the message has been replicated to every secondary.
 *
 * Append + replicate is serialized (one message fully committed at a time)
 * so that concurrent POSTs cannot interleave and every secondary ends up
 * with the same order as the master.
 */
public final class ReplicatedLog {

    private static final Logger log = LoggerFactory.getLogger(ReplicatedLog.class);

    private final MessageStore store = new MessageStore();
    private final ReplicationCoordinator coordinator;
    private final AtomicLong nextId = new AtomicLong(1);

    public ReplicatedLog(ReplicationCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public synchronized Message appendAndReplicate(String text) {
        Message message = new Message(nextId.getAndIncrement(), text, System.currentTimeMillis());

        store.append(message);
        log.info("Appended message id={} to master log, replicating to secondaries", message.getId());

        coordinator.replicateToAll(message);

        return message;
    }

    public String toJsonArray() {
        return store.toJsonArray();
    }
}
