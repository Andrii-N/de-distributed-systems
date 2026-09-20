package com.distsys.replicatedlog.common;

import org.junit.jupiter.api.Test;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MessageTest {
    
    @Test 
    void roundTripsThroughJson() {
        String uuid = UUID.randomUUID().toString();
        Message original = new Message(uuid, "hello", 123_000_000_000L);

        JsonObject json = original.toJson();
        Message parsed = Message.fromJson(json);

        assertEquals(original, parsed);
        assertEquals(uuid, parsed.getId());
        assertEquals("hello", parsed.getText());
        assertEquals(123_000_000_000L, parsed.getTimestamp());
    }

    @Test
    void parsesFromRawJsonString() {
        JsonObject jsonObject = JsonParser.parseString("{\"id\":\"12345\",\"text\":\"hi\",\"timestamp\":123}").getAsJsonObject();

        Message parsed = Message.fromJson(jsonObject);

        assertEquals(new Message("12345", "hi", 123L), parsed);
    }
}
