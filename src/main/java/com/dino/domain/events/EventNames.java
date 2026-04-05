package com.dino.domain.events;

/**
 * Catálogo de eventos internos publicados en el {@code EventBus}.
 *
 * <p>Se usa como contrato informal entre la lógica del host, la interfaz y el
 * audio. Mantener todos los nombres en un solo lugar evita errores por cadenas
 * duplicadas o mal escritas.</p>
 */
public interface EventNames {
    String PLAYER_JOINED = "PLAYER_JOINED";
    String PLAYER_READY = "PLAYER_READY";
    String GAME_STARTED = "GAME_STARTED";
    String SNAPSHOT_RECEIVED = "SNAPSHOT_RECEIVED";
    String BUTTON_STATE_CHANGED = "BUTTON_STATE_CHANGED";
    String PLAYER_DIED = "PLAYER_DIED";
    String PLAYER_JUMPED = "PLAYER_JUMPED";
    String PLAYER_COLLIDED = "PLAYER_COLLIDED";
    String THREAD_STRETCHED = "THREAD_STRETCHED";
    String PLAYER_REACHED_EXIT = "PLAYER_REACHED_EXIT";
    String SCORE_CHANGED = "SCORE_CHANGED";
    String ROOM_RESET = "ROOM_RESET";
    String LEVEL_ADVANCED = "LEVEL_ADVANCED";
    String LEVEL_COMPLETED = "LEVEL_COMPLETED";
    String GAME_OVER = "GAME_OVER";
    String PLAYER_DISCONNECTED = "PLAYER_DISCONNECTED";
    String COIN_COLLECTED = "COIN_COLLECTED";
}
