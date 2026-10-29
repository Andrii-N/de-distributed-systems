package com.distsys.replicatedlog.secondary;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.distsys.replicatedlog.common.Message;
import com.distsys.replicatedlog.common.MessageStore;

public class SecondaryNode {

    private static final Logger log = LoggerFactory.getLogger(SecondaryNode.class);

    private final MessageStore messages = new MessageStore();
    private final long replicationDelayMs;

    public SecondaryNode(long replicationDelayMs) {
        this.replicationDelayMs = replicationDelayMs;
    }

    public long getReplicationDelayMs() {
        return replicationDelayMs;
    }

    /**
     * Copy of the replicated messages, so callers can't append around {@link #receiveMessage}
     */
    public List<Message> listMsgs() {
        return messages.snapshot();
    }

    public void receiveMessage(Message message) {
        /* Replicate message*/
        messages.append(message);
        
        /* Delay after replication, prior to ack - so GET mid-delay includes message */
        log.info("Received message id={} for replication, simulating delay of {}ms", message.getId(), replicationDelayMs);
        sleep(replicationDelayMs);
    }


    private static void sleep(long milliseconds) {
        if (milliseconds <= 0) {
            return;
        }
        try {
            Thread.sleep(milliseconds);
        }
        catch(InterruptedException e) {
            /* Restore interrupted indication before doing anything else */
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while simulating replication delay", e); 
        }
    }
}
