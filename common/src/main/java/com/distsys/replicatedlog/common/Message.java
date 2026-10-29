package com.distsys.replicatedlog.common;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * Record in Replicated Log
 */
public final class Message {

    private final long id;
    private final String text;
    private final long timestamp;

    private static final Gson GSON = new Gson();

    public Message(long id, String text, long timestamp) {
        this.id = id;
        this.text = text;
        this.timestamp = timestamp;
    }

    public long getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public JsonObject toJson() {
        return GSON.toJsonTree(this).getAsJsonObject();
    }

    public static Message fromJson(JsonObject json) {
        for (String field : new String[] {"id", "text", "timestamp"}) {
            if (!json.has(field) || json.get(field).isJsonNull()) {
                throw new IllegalArgumentException("Missing required field: " + field);
            }
        }
        validateId(json.get("id"));
        return GSON.fromJson(json, Message.class);
    }

    /**
     * Gson alone would coerce "12345" to 12345, truncate 2.5 to 2 and overflow huge numbers silently,
     * so the id is checked first: a JSON number, integral, fits in a long, and >= 1 (master's sequence starts at 1)
     */
    private static void validateId(JsonElement idElement) {
        if (!idElement.isJsonPrimitive() || !idElement.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Field id must be a JSON number, got: " + idElement);
        }
        long id;
        try {
            id = idElement.getAsBigDecimal().longValueExact();
        }
        catch (ArithmeticException e) {
            throw new IllegalArgumentException("Field id must be an integer that fits in a long, got: " + idElement, e);
        }
        if (id < 1) {
            throw new IllegalArgumentException("Field id must be >= 1, got: " + id);
        }
    }

    /** 
     * Two messages are equal when id, text and timestamp match, so a copy received from the master compares equal to the original
    */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Message message)) {
            return false;
        }
        return id == message.id && timestamp == message.timestamp && Objects.equals(text, message.text);
    }

    @Override 
    public int hashCode() {
        return Objects.hash(id, text, timestamp);
    }

    @Override
    public String toString() {
        return toJson().toString();
    }
}
