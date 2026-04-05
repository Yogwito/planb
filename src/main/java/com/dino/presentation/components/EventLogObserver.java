package com.dino.presentation.components;

import com.dino.application.services.EventBus;
import com.dino.domain.events.EventNames;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class EventLogObserver {
    private static final int MAX = 5;
    private final Deque<String> entries = new ArrayDeque<>();

    public EventLogObserver(EventBus eventBus) {
        eventBus.subscribe(EventNames.BUTTON_STATE_CHANGED, e -> add(Boolean.TRUE.equals(e.get("pressed"))
            ? "Boton activado"
            : "Boton liberado"));
        eventBus.subscribe(EventNames.PLAYER_DIED, e -> add("Caida de " + e.getOrDefault("playerId", "?")));
        eventBus.subscribe(EventNames.PLAYER_REACHED_EXIT, e -> add(
            e.getOrDefault("playerId", "?") + " llego #" + e.getOrDefault("finishOrder", "?")));
        eventBus.subscribe(EventNames.SCORE_CHANGED, e -> {
            int delta = ((Number) e.getOrDefault("delta", 0)).intValue();
            String sign = delta >= 0 ? "+" : "";
            add(e.getOrDefault("playerId", "?") + " " + sign + delta + " pts");
        });
        eventBus.subscribe(EventNames.ROOM_RESET, e -> add("Sala reiniciada"));
        eventBus.subscribe(EventNames.LEVEL_COMPLETED, e -> add("Todos llegaron a la salida"));
    }

    private void add(String msg) {
        entries.addFirst(msg);
        while (entries.size() > MAX) entries.removeLast();
    }

    public List<String> getEntries() {
        return new ArrayList<>(entries);
    }
}
