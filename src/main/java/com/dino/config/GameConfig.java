package com.dino.config;

import javafx.scene.paint.Color;
import java.util.Map;

public final class GameConfig {
    public static final int WINDOW_WIDTH  = 1280;
    public static final int WINDOW_HEIGHT = 780;
    public static final int FPS = 60;
    public static final int GAME_DURATION_SECONDS = 60;
    public static final int SNAPSHOT_RATE_HZ = 10;
    public static final double PLAYER_SPEED = 180.0;
    public static final double PENALTY_MULTIPLIER = 0.4;
    public static final double PENALTY_DURATION = 3.0;
    public static final int ARENA_X = 20;
    public static final int ARENA_Y = 20;
    public static final int ARENA_W = 860;
    public static final int ARENA_H = 580;
    public static final int MAX_PLAYERS = 4;
    public static final int ITEM_COUNT = 8;
    public static final int ITEM_POINTS = 10;
    public static final int PENALTY_POINTS = -5;

    public static final Map<String, Color> COLORS = Map.of(
        "red",    Color.web("#e94560"),
        "blue",   Color.web("#4fc3f7"),
        "green",  Color.web("#81c784"),
        "yellow", Color.web("#ffb74d")
    );

    private GameConfig() {}
}
