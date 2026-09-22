package com.distsys.replicatedlog.common;


import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

import com.google.gson.JsonArray;

/**
* Thread-safe in-memory storage of {@link Message}s, appedned in the receive order.
Used by master and secondaries.
* Notes:
* modern CPUs give each core its own cache, and without synchronization, one thread's write can sit in its core's cache and never become visible to another thread reading the same field — that's the real visibility problem.
* Acquiring and releasing a lock also forces a flush of pending changes, so the next thread to lock the same object is guaranteed to see the latest state, not a stale cached copy.
* Mutual exclusion is the guarantee that only one thread can be inside a given protected operation on a given object at any instant — every other thread that wants in has to wait its turn, one at a time, never simultaneously.
* No ordering promise. If thread A and thread B both call add at nearly the same instant, mutual exclusion guarantees they don't corrupt anything and both messages end up in the list — but not which one gets in first. 
*/
public final class MessageStore {
    
    /**
    * Plain ArrayList in a decorator that puts a synchronized block around each individual method — add, get, size, and so on all lock internally.
    * I.e. two threads calling .add() at once will run one after the other, never interleaved. 
    * Works fine with single method invocation.
    */
    private final List<Message> messages = Collections.synchronizedList(new ArrayList<Message>());

    public void append(Message message) {
        messages.add(message);
    }

    /**
    * Pass a copy of messages in a synchronized block prior to sequential method calls (more then one method call)
    */
    public List<Message> snapshot() {
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }

    public String toJsonArray() {
        JsonArray jsonArray = new JsonArray();
        /**
         * Iterates the copy outside any lock
         */
        for (Message message : snapshot()) {
            jsonArray.add(message.toJson());
        }

        return jsonArray.toString();
    }

    public int size() {
        return messages.size();
    }
    
}
