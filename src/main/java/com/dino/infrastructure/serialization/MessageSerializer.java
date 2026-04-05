package com.dino.infrastructure.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MessageSerializer {
    private final ObjectMapper mapper = new ObjectMapper();

    public static final String JOIN           = "JOIN";
    public static final String WELCOME        = "WELCOME";
    public static final String READY          = "READY";
    public static final String LOBBY_SNAPSHOT = "LOBBY_SNAPSHOT";
    public static final String START_GAME     = "START_GAME";
    public static final String INPUT          = "INPUT";
    public static final String SNAPSHOT       = "SNAPSHOT";
    public static final String GAME_EVENT     = "GAME_EVENT";
    public static final String HEARTBEAT      = "HEARTBEAT";
    public static final String DISCONNECT     = "DISCONNECT";
    public static final String GAME_OVER      = "GAME_OVER";

    public byte[] serialize(Map<String, Object> msg) {
        try {
            return mapper.writeValueAsBytes(msg);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> deserialize(byte[] data) {
        try {
            return mapper.readValue(data, Map.class);
        } catch (IOException e) {
            return new HashMap<>();
        }
    }

    public Map<String, Object> build(String type, Object... keyValuePairs) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", type);
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            msg.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
        }
        return msg;
    }
}
