package com.distsys.replicatedlog.secondary;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.distsys.replicatedlog.common.Message;
import com.distsys.replicatedlog.common.MessageStore;

/**
 * SecondaryNode
 * replication logic of a secondary, no HTTP; used by the HTTP adapter and the test harness
 */
public class SecondaryNode {

    private static final Logger log = LoggerFactory.getLogger(SecondaryNode.class);

    private final MessageStore messages = new MessageStore();
    /* Logical name (e.g. "secondary1"); the id the master and the transport address this node by */
    private final String nodeId;
    private final long replicationDelayMs;

    public SecondaryNode(String nodeId, long replicationDelayMs) {
        this.nodeId = nodeId;
        this.replicationDelayMs = replicationDelayMs;
    }

    public String getNodeId() {
        return nodeId;
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
        log.info("{} stored message id={}, simulating delay of {}ms", nodeId, message.getId(), replicationDelayMs);
        
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
