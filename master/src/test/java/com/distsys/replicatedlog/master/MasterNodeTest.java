package com.distsys.replicatedlog.master;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import com.distsys.replicatedlog.common.Message;
import com.distsys.replicatedlog.common.ReplicationException;
import com.distsys.replicatedlog.common.Transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Master logic without HTTP: the coordinator runs on a lambda transport
 */
public class MasterNodeTest {

    @Test
    void assignsSequentialIdsAndListsMessagesInOrder() {
        MasterNode master = masterWith((from, to, message) -> CompletableFuture.completedFuture(null));

        Message first = master.appendMsg("first");
        Message second = master.appendMsg("second");
        Message third = master.appendMsg("third");

        assertEquals(List.of(1L, 2L, 3L), List.of(first.getId(), second.getId(), third.getId()));
        assertEquals(List.of(first, second, third), master.listMsgs());
    }

    @Test
    void replicatesEveryAppendedMessage() {
        List<Long> sentIds = new CopyOnWriteArrayList<>();
        MasterNode master = masterWith((from, to, message) -> {
            sentIds.add(message.getId());
            return CompletableFuture.completedFuture(null);
        });

        master.appendMsg("first");
        master.appendMsg("second");

        assertEquals(List.of(1L, 2L), sentIds);
    }

    @Test
    void listMsgsReturnsCopyThatDoesNotChangeTheNode() {
        MasterNode master = masterWith((from, to, message) -> CompletableFuture.completedFuture(null));
        master.appendMsg("first");

        master.listMsgs().add(new Message(99, "injected", 1L));

        assertEquals(1, master.listMsgs().size());
    }

    /**
     * Iteration 1 behaviour, documented rather than endorsed: the message is appended before replication,
     * so a failed replication (client gets 502) still leaves it in the master's log. Revisit in Milestone 5.
     */
    @Test
    void failedReplicationThrowsButKeepsMessageInMasterLog() {
        MasterNode master = masterWith((from, to, message) ->
                CompletableFuture.failedFuture(new ReplicationException("simulated failure")));

        assertThrows(ReplicationException.class, () -> master.appendMsg("lost?"));

        assertEquals(1, master.listMsgs().size());
        assertEquals("lost?", master.listMsgs().get(0).getText());
    }

    private static MasterNode masterWith(Transport transport) {
        return new MasterNode(new ReplicationCoordinator(transport, "master", List.of("secondary1")));
    }
}
