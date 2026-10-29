package com.distsys.replicatedlog.common;

import org.junit.jupiter.api.Test;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MessageTest {
    
    @Test 
    void roundTripsThroughJson() {
        long id = 1;
        Message original = new Message(id, "hello", 123_000_000_000L);

        JsonObject json = original.toJson();
        Message parsed = Message.fromJson(json);

        assertEquals(original, parsed);
        assertEquals(id, parsed.getId());
        assertEquals("hello", parsed.getText());
        assertEquals(123_000_000_000L, parsed.getTimestamp());
    }

    @Test
    void parsesFromRawJsonString() {
        JsonObject jsonObject = JsonParser.parseString("{\"id\":12345,\"text\":\"hi\",\"timestamp\":123}").getAsJsonObject();

        Message parsed = Message.fromJson(jsonObject);

        assertEquals(new Message(12345, "hi", 123L), parsed);
    }

    @Test
    void rejectsJsonWithMissingFields() {
        JsonObject json = JsonParser.parseString("{\"text\":\"hi\"}").getAsJsonObject();

        assertThrows(IllegalArgumentException.class, () -> Message.fromJson(json));
    }   

}
