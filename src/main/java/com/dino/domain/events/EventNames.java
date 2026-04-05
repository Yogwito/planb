package com.dino.domain.events;

public interface EventNames {
    String PLAYER_JOINED       = "PLAYER_JOINED";
    String PLAYER_READY        = "PLAYER_READY";
    String GAME_STARTED        = "GAME_STARTED";
    String ITEM_COLLECTED      = "ITEM_COLLECTED";
    String PENALTY_APPLIED     = "PENALTY_APPLIED";
    String SCORE_UPDATED       = "SCORE_UPDATED";
    String GAME_OVER           = "GAME_OVER";
    String PLAYER_DISCONNECTED = "PLAYER_DISCONNECTED";
    String SNAPSHOT_RECEIVED   = "SNAPSHOT_RECEIVED";
}
