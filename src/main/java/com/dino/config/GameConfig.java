package com.dino.config;

import javafx.scene.paint.Color;

import java.util.Map;

/**
 * Configuración central del juego.
 *
 * <p>Reúne constantes de ventana, física, red, cámara, puntaje y elementos del
 * nivel. Se usa para evitar números mágicos dispersos y facilitar el ajuste del
 * balance sin tocar la lógica de las clases principales.</p>
 */
public final class GameConfig {
    /** Ancho fijo de la ventana principal. */
    public static final int WINDOW_WIDTH = 1280;
    /** Alto fijo de la ventana principal. */
    public static final int WINDOW_HEIGHT = 780;
    /** Frecuencia objetivo de render. */
    public static final int FPS = 60;
    /** Frecuencia con la que el host difunde snapshots UDP. */
    public static final int SNAPSHOT_RATE_HZ = 30;

    public static final int LEVEL_WIDTH = 1800;
    public static final int LEVEL_HEIGHT = 900;
    public static final double VIEWPORT_W = 980.0;
    public static final double VIEWPORT_H = 620.0;

    public static final int MAX_PLAYERS = 4;
    public static final double PLAYER_WIDTH = 42.0;
    public static final double PLAYER_HEIGHT = 52.0;
    public static final double MOVE_SPEED = 260.0;
    public static final double MOVE_ACCELERATION = 1850.0;
    public static final double AIR_MOVE_ACCELERATION = 1100.0;
    public static final double MOVE_FRICTION = 2200.0;
    public static final double JUMP_VELOCITY = -560.0;
    public static final double GRAVITY = 1480.0;
    public static final double COYOTE_TIME_SECONDS = 0.11;
    public static final double JUMP_BUFFER_SECONDS = 0.12;
    public static final double FALL_RESET_Y = LEVEL_HEIGHT + 120.0;
    public static final double THREAD_MAX_DISTANCE = 300.0;
    public static final double THREAD_REST_DISTANCE = 200.0;
    public static final double THREAD_TENSE_DISTANCE = 245.0;
    public static final double THREAD_CRITICAL_DISTANCE = 292.0;
    public static final double THREAD_PULL_FACTOR = 6.4;
    public static final double THREAD_DAMPING = 2.4;
    public static final double THREAD_VERTICAL_PULL = 0.16;
    public static final double THREAD_HARD_LIMIT = 340.0;
    public static final double PLAYER_COLLISION_CONTACT_MARGIN = 9.0;
    public static final double PLAYER_COLLISION_VELOCITY_DAMPING = 0.72;
    public static final double PLAYER_COLLISION_CARRY_RATIO = 0.35;
    public static final double TARGET_REACHED_TOLERANCE = 8.0;
    public static final double MOUSE_JUMP_VERTICAL_THRESHOLD = 55.0;
    public static final double CLIENT_INPUT_RESEND_SECONDS = 0.033;
    public static final double CLIENT_HEARTBEAT_SECONDS = 0.35;
    public static final double HOST_PEER_TIMEOUT_SECONDS = 2.4;
    public static final double REMOTE_SMOOTHING = 0.18;
    public static final double VISUAL_STRETCH_SMOOTHING = 0.2;
    public static final double REMOTE_PREDICTION_SECONDS = 0.10;
    public static final double LOCAL_CLIENT_PREDICTION_SECONDS = 0.06;
    public static final double SNAPSHOT_INTERPOLATION_DELAY_SECONDS = 0.09;
    public static final int MAX_BUFFERED_SNAPSHOTS = 8;
    public static final double CRITICAL_ACK_TIMEOUT_SECONDS = 2.0;
    public static final double CAMERA_GROUP_INFLUENCE = 0.26;
    public static final double SNAPSHOT_STALE_WARNING_SECONDS = 0.28;
    public static final int TOTAL_LEVELS = 5;

    public static final double BUTTON_WIDTH = 80.0;
    public static final double BUTTON_HEIGHT = 16.0;
    public static final double DOOR_WIDTH = 56.0;
    public static final double DOOR_HEIGHT = 148.0;
    public static final double EXIT_WIDTH = 120.0;
    public static final double EXIT_HEIGHT = 110.0;
    public static final double PUSH_BLOCK_WIDTH = 68.0;
    public static final double PUSH_BLOCK_HEIGHT = 68.0;
    public static final double PUSH_BLOCK_GRAVITY = 1480.0;
    public static final double PUSH_BLOCK_FRICTION = 1800.0;
    public static final double PUSH_BLOCK_MAX_SPEED = 220.0;
    public static final double PUSH_BLOCK_PUSH_IMPULSE = 0.78;

    public static final double COIN_SIZE = 16.0;
    public static final int SCORE_COIN_SMALL = 10;
    public static final int SCORE_COIN_LARGE = 25;

    public static final int SCORE_BUTTON_PRESS = 25;
    public static final int SCORE_FIRST_EXIT = 100;
    public static final int SCORE_SECOND_EXIT = 70;
    public static final int SCORE_LATE_EXIT = 50;
    public static final int SCORE_FALL_PENALTY = 15;

    public static final double CAMERA_SMOOTHING = 0.16;
    public static final double BASE_ZOOM = 1.0;
    public static final double MIN_ZOOM = 0.92;
    public static final double MAX_ZOOM = 1.05;

    /** Colores base disponibles para identificar a cada jugador. */
    public static final Map<String, Color> COLORS = Map.of(
        "red", Color.web("#ef476f"),
        "blue", Color.web("#4cc9f0"),
        "green", Color.web("#80ed99"),
        "yellow", Color.web("#ffd166")
    );

    /**
     * Evita instanciación accidental; la clase actúa como contenedor estático.
     */
    private GameConfig() {}
}
