package com.dino;

import com.dino.infrastructure.serialization.MessageSerializer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageSerializerTest {

    private final MessageSerializer serializer = new MessageSerializer();

    @Test
    void testSerializeDeserializeRoundtrip() {
        Map<String, Object> original = Map.of("type", "TEST", "value", 42, "name", "Alice");
        byte[] bytes = serializer.serialize(original);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        Map<String, Object> result = serializer.deserialize(bytes);
        assertEquals("TEST", result.get("type"));
        assertEquals(42, ((Number) result.get("value")).intValue());
        assertEquals("Alice", result.get("name"));
    }

    @Test
    void testDeserializeInvalidBytes() {
        byte[] invalid = "not json {{{{".getBytes();
        Map<String, Object> result = serializer.deserialize(invalid);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testBuildAddsTypeKey() {
        Map<String, Object> msg = serializer.build("JOIN", "playerId", "p1", "name", "Bob");
        assertEquals("JOIN", msg.get("type"));
        assertEquals("p1",  msg.get("playerId"));
        assertEquals("Bob", msg.get("name"));
    }
}
