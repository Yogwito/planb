package com.dino.infrastructure.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Serializador y fábrica básica de mensajes de red.
 *
 * <p>Convierte mapas a JSON y viceversa usando Jackson. También concentra los
 * tipos de mensaje del protocolo UDP para que host y cliente hablen el mismo
 * lenguaje.</p>
 */
public class MessageSerializer {
    private final ObjectMapper mapper = new ObjectMapper();

    /** Mensaje de entrada al lobby enviado por un cliente nuevo. */
    public static final String JOIN           = "JOIN";
    public static final String WELCOME        = "WELCOME";
    public static final String READY          = "READY";
    public static final String LOBBY_SNAPSHOT = "LOBBY_SNAPSHOT";
    public static final String START_GAME     = "START_GAME";
    public static final String INPUT          = "INPUT";
    public static final String SNAPSHOT       = "SNAPSHOT";
    public static final String GAME_EVENT     = "GAME_EVENT";
    public static final String HEARTBEAT      = "HEARTBEAT";
    public static final String ACK            = "ACK";
    public static final String DISCONNECT     = "DISCONNECT";
    public static final String GAME_OVER      = "GAME_OVER";

    /**
     * Serializa un mensaje listo para enviarse por UDP.
     *
     * @param msg mapa con el contenido del datagrama
     * @return arreglo de bytes JSON; vacío si ocurre un error
     */
    public byte[] serialize(Map<String, Object> msg) {
        try {
            return mapper.writeValueAsBytes(msg);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /**
     * Reconstruye un mapa a partir de bytes JSON recibidos por red.
     *
     * @param data bytes crudos del datagrama
     * @return mapa deserializado o un mapa vacío si el contenido era inválido
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> deserialize(byte[] data) {
        try {
            return mapper.readValue(data, Map.class);
        } catch (IOException e) {
            return new HashMap<>();
        }
    }

    /**
     * Construye un mensaje agregando automáticamente la clave {@code type}.
     *
     * @param type tipo lógico del mensaje
     * @param keyValuePairs lista alternada clave/valor
     * @return mapa listo para serializar o enviar
     */
    public Map<String, Object> build(String type, Object... keyValuePairs) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", type);
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            msg.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
        }
        return msg;
    }
}
