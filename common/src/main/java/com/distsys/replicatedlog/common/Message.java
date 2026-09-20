package com.distsys.replicatedlog.common;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * Record in Replicated Log
 */
public final class Message {

    private final String id;
    private final String text;
    private final long timestamp;

    public Message(String id, String text, long timestamp) {
        this.id = id;
        this.text = text;
        this.timestamp = timestamp;
    }

    public String getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public JsonObject toJson() {
        Gson gson = new Gson();
        return gson.toJsonTree(this).getAsJsonObject();
    }

    public static Message fromJson(JsonObject json) {
        for (String field : new String[] {"id", "text", "timestamp"}) {
            if (!json.has(field) || json.get(field).isJsonNull()) {
                throw new IllegalArgumentException("Missing required field: " + field);
            }
        }
        Gson gson = new Gson();
        return gson.fromJson(json, Message.class);
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
        return Objects.equals(id, message.id) && timestamp == message.timestamp && Objects.equals(text, message.text);
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
