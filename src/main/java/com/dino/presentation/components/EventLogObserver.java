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
            add("+" + pts + "  " + player + " recogió ítem");
        });
        eventBus.subscribe(EventNames.PENALTY_APPLIED, e -> {
            String player = (String) e.getOrDefault("playerId", "?");
            int pts = e.containsKey("points") ? ((Number) e.get("points")).intValue() : 0;
            add(pts + "  " + player + " en zona penalización");
        });
        eventBus.subscribe(EventNames.SCORE_UPDATED, e -> {
            String player = (String) e.getOrDefault("playerId", "?");
            int score = e.containsKey("score") ? ((Number) e.get("score")).intValue() : 0;
            add(player + " → " + score + " pts");
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
