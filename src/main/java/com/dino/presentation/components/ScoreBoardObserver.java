package com.dino.presentation.components;

import com.dino.application.services.EventBus;
import com.dino.domain.entities.Player;
import com.dino.domain.events.EventNames;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ScoreBoardObserver {
    private final List<Player> entries = new ArrayList<>();

    public ScoreBoardObserver(EventBus eventBus) {
        eventBus.subscribe(EventNames.SNAPSHOT_RECEIVED, this::onSnapshot);
        eventBus.subscribe(EventNames.GAME_OVER, this::onSnapshot);
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
            p.setAtExit((Boolean) pd.getOrDefault("atExit", false));
            p.setAlive((Boolean) pd.getOrDefault("alive", true));
            p.setConnected((Boolean) pd.getOrDefault("connected", true));
            p.setScore(((Number) pd.getOrDefault("score", 0)).intValue());
            p.setDeaths(((Number) pd.getOrDefault("deaths", 0)).intValue());
            p.setFinishOrder(((Number) pd.getOrDefault("finishOrder", 0)).intValue());
            entries.add(p);
        }
        entries.sort((a, b) -> {
            if (a.getScore() != b.getScore()) return Integer.compare(b.getScore(), a.getScore());
            if (a.getFinishOrder() != b.getFinishOrder()) {
                int orderA = a.getFinishOrder() == 0 ? Integer.MAX_VALUE : a.getFinishOrder();
                int orderB = b.getFinishOrder() == 0 ? Integer.MAX_VALUE : b.getFinishOrder();
                return Integer.compare(orderA, orderB);
            }
            return Integer.compare(a.getDeaths(), b.getDeaths());
        });
    }

    public List<Player> getEntries() {
        return Collections.unmodifiableList(entries);
    }
}
