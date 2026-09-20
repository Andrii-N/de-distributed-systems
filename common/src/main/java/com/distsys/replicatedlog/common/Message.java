package com.distsys.replicatedlog.common;

import java.util.UUID;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;


/**
 * Record in Replicated Log
 */
public final class Message { 
    private final String id;
    private final String message;
    private final long timestamp;

    public Message(UUID id, String message, long timestamp) {
        this.id = UUID.randomUUID().toString();
        this.message = message;
        this.timestamp = timestamp;
    }

    public String getId() {
        return id;
    }

    public String getMessage() {
        return message;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public JsonObject toJson () {
        Gson gson = new Gson();
        return gson.toJson(this)


}
