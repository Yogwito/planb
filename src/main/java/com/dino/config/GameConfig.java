package com.dino.config;

import javafx.scene.paint.Color;
import java.util.Map;

public final class GameConfig {
    public static final int WINDOW_WIDTH  = 1280;
    public static final int WINDOW_HEIGHT = 780;
    public static final int FPS = 60;
    public static final int GAME_DURATION_SECONDS = 120;
    public static final int SNAPSHOT_RATE_HZ = 10;
    public static final int ARENA_X = 20;
    public static final int ARENA_Y = 20;
    public static final int ARENA_W = 2600;
    public static final int ARENA_H = 1800;
    public static final int MAX_PLAYERS = 4;
    public static final double VIEWPORT_W = 900.0;
    public static final double VIEWPORT_H = 620.0;

    public static final double PLAYER_START_MASS = 28.0;
    public static final double PLAYER_RESPAWN_MASS = 24.0;
    public static final double PLAYER_MIN_MASS = 18.0;
    public static final double PLAYER_BASE_SPEED = 230.0;
    public static final double PLAYER_MIN_SPEED = 70.0;
    public static final double PLAYER_RADIUS_SCALE = 3.1;
    public static final double PLAYER_EAT_RATIO = 1.18;
    public static final double PLAYER_EAT_OVERLAP_MARGIN = 0.85;
    public static final double PLAYER_DECAY_THRESHOLD = 90.0;
    public static final double PLAYER_DECAY_RATE = 0.018;
    public static final double PLAYER_MASS_TRANSFER_RATIO = 0.88;

    public static final int FOOD_INITIAL_COUNT = 180;
    public static final int FOOD_MAX_COUNT = 240;
    public static final double FOOD_SPAWN_PER_SECOND = 18.0;
    public static final int FOOD_MASS = 4;
    public static final double FOOD_RADIUS = 6.0;
    public static final double FOOD_MIN_PLAYER_DISTANCE = 55.0;
    public static final double FOOD_MIN_VIRUS_DISTANCE = 24.0;

    public static final int VIRUS_COUNT = 12;
    public static final double VIRUS_RADIUS = 28.0;
    public static final double VIRUS_TRIGGER_MASS = 92.0;
    public static final double VIRUS_BURST_RATIO = 0.38;
    public static final int VIRUS_FEED_DROP_COUNT = 8;
    public static final double VIRUS_MIN_PLAYER_DISTANCE = 140.0;
    public static final double VIRUS_MIN_VIRUS_DISTANCE = 120.0;

    public static final Map<String, Color> COLORS = Map.of(
        "red",    Color.web("#e94560"),
        "blue",   Color.web("#4fc3f7"),
        "green",  Color.web("#81c784"),
        "yellow", Color.web("#ffb74d")
    );

    private GameConfig() {}
}
