package com.desystems.replicatedlog.common;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-safe in-memory list of {@link Message}s, appended in receipt order.
 * Used both by the master (the authoritative log) and by each secondary
 * (the replicated copy).
 */
public final class MessageStore {

    private final List<Message> messages = Collections.synchronizedList(new ArrayList<>());

    public void append(Message message) {
        messages.add(message);
    }

    public List<Message> snapshot() {
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }

    public String toJsonArray() {
        JSONArray array = new JSONArray();
        for (Message message : snapshot()) {
            array.put(message.toJson());
        }
        return array.toString();
    }

    public int size() {
        return messages.size();
    }
}
