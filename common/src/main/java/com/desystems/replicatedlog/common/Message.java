package com.desystems.replicatedlog.common;

import org.json.JSONObject;

import java.util.Objects;

/**
 * A single entry in the replicated log.
 */
public final class Message {

    private final long id;
    private final String text;
    private final long timestamp;

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

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("text", text);
        json.put("timestamp", timestamp);
        return json;
    }

    public static Message fromJson(JSONObject json) {
        return new Message(json.getLong("id"), json.getString("text"), json.getLong("timestamp"));
    }

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
