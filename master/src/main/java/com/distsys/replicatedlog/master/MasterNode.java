package com.distsys.replicatedlog.master;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.distsys.replicatedlog.common.MessageStore;
import com.distsys.replicatedlog.common.Message;

/**
 * The master's authoritative log, no HTTP; used by the HTTP adapter and the test harness.
 * {@link #appendMsg} assigns the id, appends locally and blocks until the message has been
 * replicated to every secondary; {@link #listMsgs} returns the log in id order.
 */
public final class MasterNode {

    private static final Logger log = LoggerFactory.getLogger(MasterNode.class);

    private final MessageStore messages = new MessageStore();
    /* Thread-safe auto-increment id */
    private final AtomicLong nextId = new AtomicLong(1);
    private final ReplicationCoordinator coordinator;

    public MasterNode(ReplicationCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    /**
     * Stays synchronized on purpose (Iteration 1 behaviour); Milestone 4 narrows the lock.
     * The message is appended before replication, so it stays in the master's log even when replication fails.
     */
    public synchronized Message appendMsg(String text) {
        Message message = new Message(nextId.getAndIncrement(), text, System.currentTimeMillis());

        messages.append(message);
        log.info("Appended message id={} to master log, replicating to secondaries", message.getId());
        coordinator.replicateToAll(message);

        return message;
    }

    /**
     * Copy of the master's log, so callers can't append around {@link #appendMsg}
     */
    public List<Message> listMsgs() {
        return messages.snapshot();
    }
}
