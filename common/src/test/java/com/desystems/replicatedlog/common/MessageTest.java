package com.desystems.replicatedlog.common;

import org.junit.jupiter.api.Test;
import org.json.JSONObject;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageTest {

    @Test
    void roundTripsThroughJson() {
        Message original = new Message(42L, "hello world", 1_700_000_000_000L);

        JSONObject json = original.toJson();
        Message parsed = Message.fromJson(json);

        assertEquals(original, parsed);
        assertEquals(42L, parsed.getId());
        assertEquals("hello world", parsed.getText());
        assertEquals(1_700_000_000_000L, parsed.getTimestamp());
    }

    @Test
    void parsesFromRawJsonString() {
        JSONObject json = new JSONObject("{\"id\":1,\"text\":\"hi\",\"timestamp\":123}");

        Message parsed = Message.fromJson(json);

        assertEquals(new Message(1L, "hi", 123L), parsed);
    }
}
