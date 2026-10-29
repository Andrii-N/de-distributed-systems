package com.distsys.replicatedlog.secondary;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.distsys.replicatedlog.common.Message;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Node logic without HTTP: no ports, no server, called directly
 */
public class SecondaryNodeTest {

    @Test
    void listsReceivedMessagesInArrivalOrder() {
        SecondaryNode node = new SecondaryNode(0);

        node.receiveMessage(new Message(1, "first", 1L));
        node.receiveMessage(new Message(2, "second", 2L));

        assertEquals(List.of(new Message(1, "first", 1L), new Message(2, "second", 2L)), node.listMsgs());
    }

    @Test
    void listMsgsReturnsCopyThatDoesNotChangeTheNode() {
        SecondaryNode node = new SecondaryNode(0);
        node.receiveMessage(new Message(1, "first", 1L));

        List<Message> listed = node.listMsgs();
        listed.add(new Message(2, "injected", 2L));

        assertEquals(1, node.listMsgs().size());
    }

    @Test
    void receiveBlocksForConfiguredDelay() {
        long delayMs = 300;
        SecondaryNode node = new SecondaryNode(delayMs);

        long start = System.currentTimeMillis();
        node.receiveMessage(new Message(1, "slow", 1L));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= delayMs, "expected at least " + delayMs + "ms, took " + elapsed + "ms");
    }

    /**
     * Iteration 1 behaviour: append first, then sleep. Roadmap Milestone 9 flips this on purpose,
     * and this test should then be rewritten to assert the message is NOT visible during the delay.
     */
    @Test
    void messageIsVisibleWhileDelayIsStillRunning() throws Exception {
        SecondaryNode node = new SecondaryNode(1000);

        CompletableFuture<Void> receive = CompletableFuture.runAsync(() -> node.receiveMessage(new Message(1, "slow", 1L)));

        long deadline = System.currentTimeMillis() + 500;
        while (node.listMsgs().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertEquals(1, node.listMsgs().size());
        assertFalse(receive.isDone(), "receive should still be sleeping when the message becomes visible");
        receive.get(5, TimeUnit.SECONDS);
    }

    @Test
    void interruptDuringDelayThrowsAndRestoresInterruptFlag() throws InterruptedException {
        SecondaryNode node = new SecondaryNode(10_000);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptFlagAfter = new AtomicBoolean();

        Thread receiver = new Thread(() -> {
            try {
                node.receiveMessage(new Message(1, "slow", 1L));
            }
            catch (RuntimeException e) {
                thrown.set(e);
            }
            interruptFlagAfter.set(Thread.currentThread().isInterrupted());
        });
        receiver.start();
        Thread.sleep(100);
        receiver.interrupt();
        receiver.join(5_000);

        assertFalse(receiver.isAlive(), "receiver should stop soon after being interrupted");
        assertInstanceOf(RuntimeException.class, thrown.get());
        assertInstanceOf(InterruptedException.class, thrown.get().getCause());
        assertTrue(interruptFlagAfter.get(), "interrupt flag must be restored");
    }
}
