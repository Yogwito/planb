package com.dino.presentation.components;

import com.dino.application.services.EventBus;
import com.dino.domain.entities.Player;
import com.dino.domain.events.EventNames;

import java.util.*;

public class ScoreBoardObserver {
    private final List<Player> entries = new ArrayList<>();

    public ScoreBoardObserver(EventBus eventBus) {
        eventBus.subscribe(EventNames.SNAPSHOT_RECEIVED, this::onSnapshot);
        eventBus.subscribe(EventNames.GAME_OVER,         this::onSnapshot);
    }

    @SuppressWarnings("unchecked")
    private void onSnapshot(Map<String, Object> payload) {
        List<Map<String, Object>> playerData = (List<Map<String, Object>>) payload.get("players");
        if (playerData == null) return;
        entries.clear();
        for (Map<String, Object> pd : playerData) {
            Player p = new Player();
            p.setId((String) pd.get("id"));
            p.setName((String) pd.get("name"));
            p.setScore(((Number) pd.get("score")).intValue());
            entries.add(p);
        }
        entries.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
    }

    public List<Player> getEntries() {
        return Collections.unmodifiableList(entries);
    }
}
