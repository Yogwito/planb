package com.dino.presentation.components;

import com.dino.application.services.EventBus;
import com.dino.domain.events.EventNames;

import java.util.*;

public class EventLogObserver {
    private final Deque<String> entries = new ArrayDeque<>();
    private static final int MAX = 5;

    public EventLogObserver(EventBus eventBus) {
        eventBus.subscribe(EventNames.ITEM_COLLECTED, e -> {
            String player = (String) e.getOrDefault("playerId", "?");
            int pts = e.containsKey("points") ? ((Number) e.get("points")).intValue() : 0;
            add("+" + pts + " masa  " + player + " comio pellet");
        });
        eventBus.subscribe(EventNames.PLAYER_CONSUMED, e -> {
            String predator = (String) e.getOrDefault("playerId", "?");
            String prey = (String) e.getOrDefault("targetId", "?");
            int gained = e.containsKey("gainedMass") ? (int) Math.round(((Number) e.get("gainedMass")).doubleValue()) : 0;
            add(predator + " devoro a " + prey + " +" + gained);
        });
        eventBus.subscribe(EventNames.VIRUS_TRIGGERED, e -> {
            String player = (String) e.getOrDefault("playerId", "?");
            int lost = e.containsKey("lostMass") ? (int) Math.round(((Number) e.get("lostMass")).doubleValue()) : 0;
            add("Virus golpea a " + player + " -" + lost);
        });
        eventBus.subscribe(EventNames.SCORE_UPDATED, e -> {
            String player = (String) e.getOrDefault("playerId", "?");
            int score = e.containsKey("score") ? ((Number) e.get("score")).intValue() : 0;
            add(player + " → " + score + " masa");
        });
    }

    private void add(String msg) {
        entries.addFirst(msg);
        while (entries.size() > MAX) entries.removeLast();
    }

    public List<String> getEntries() {
        return new ArrayList<>(entries);
    }
}
