package com.dino.application.services;

import com.dino.domain.entities.CollectibleItem;
import com.dino.domain.entities.PenaltyZone;
import com.dino.domain.entities.Player;
import com.dino.domain.events.EventNames;

import java.util.*;

public class SessionService {
    private final EventBus eventBus;

    private String localPlayerId;
    private String localIp;
    private int localPort;
    private String hostIp;
    private int hostPort;
    private boolean isHost;
    private String playerName;
    private int expectedPlayers;

    private final Map<String, Player> players = new LinkedHashMap<>();
    private final List<CollectibleItem> items = new ArrayList<>();
    private final List<PenaltyZone> penaltyZones = new ArrayList<>();
    private double gameTimer;
    private boolean gameRunning;

    public SessionService(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public void addPlayer(Player player) { players.put(player.getId(), player); }
    public void removePlayer(String playerId) { players.remove(playerId); }

    @SuppressWarnings("unchecked")
    public void updateFromSnapshot(Map<String, Object> data) {
        if (data.containsKey("gameTimer"))   gameTimer   = ((Number) data.get("gameTimer")).doubleValue();
        if (data.containsKey("gameRunning")) gameRunning = (Boolean) data.get("gameRunning");

        if (data.containsKey("players")) {
            for (Map<String, Object> pd : (List<Map<String, Object>>) data.get("players")) {
                String id = (String) pd.get("id");
                Player p = players.computeIfAbsent(id, k -> new Player());
                p.setId(id);
                if (pd.get("name")  != null) p.setName((String) pd.get("name"));
                if (pd.get("color") != null) p.setColor((String) pd.get("color"));
                if (pd.get("x")     != null) p.setX(((Number) pd.get("x")).doubleValue());
                if (pd.get("y")     != null) p.setY(((Number) pd.get("y")).doubleValue());
                if (pd.get("score") != null) p.setScore(((Number) pd.get("score")).intValue());
                p.setConnected((Boolean) pd.getOrDefault("connected", true));
            }
        }

        if (data.containsKey("items")) {
            items.clear();
            for (Map<String, Object> id : (List<Map<String, Object>>) data.get("items")) {
                CollectibleItem item = new CollectibleItem();
                item.setId((String) id.get("id"));
                item.setX(((Number) id.get("x")).doubleValue());
                item.setY(((Number) id.get("y")).doubleValue());
                item.setPoints(((Number) id.get("points")).intValue());
                item.setActive((Boolean) id.getOrDefault("active", true));
                items.add(item);
            }
        }

        if (data.containsKey("penaltyZones")) {
            penaltyZones.clear();
            for (Map<String, Object> zd : (List<Map<String, Object>>) data.get("penaltyZones")) {
                PenaltyZone zone = new PenaltyZone();
                zone.setId((String) zd.get("id"));
                zone.setX(((Number) zd.get("x")).doubleValue());
                zone.setY(((Number) zd.get("y")).doubleValue());
                zone.setRadius(((Number) zd.get("radius")).doubleValue());
                zone.setPoints(((Number) zd.get("points")).intValue());
                zone.setSlowMultiplier(((Number) zd.getOrDefault("slowMultiplier", 0.4)).doubleValue());
                penaltyZones.add(zone);
            }
        }

        eventBus.publish(EventNames.SNAPSHOT_RECEIVED, data);
    }

    public Map<String, Object> getSnapshotData() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("gameTimer", gameTimer);
        snapshot.put("gameRunning", gameRunning);

        List<Map<String, Object>> playerList = new ArrayList<>();
        for (Player p : players.values()) {
            Map<String, Object> pd = new HashMap<>();
            pd.put("id", p.getId());
            pd.put("name", p.getName());
            pd.put("color", p.getColor());
            pd.put("x", p.getX());
            pd.put("y", p.getY());
            pd.put("score", p.getScore());
            pd.put("connected", p.isConnected());
            playerList.add(pd);
        }
        snapshot.put("players", playerList);

        List<Map<String, Object>> itemList = new ArrayList<>();
        for (CollectibleItem item : items) {
            Map<String, Object> id = new HashMap<>();
            id.put("id", item.getId());
            id.put("x", item.getX());
            id.put("y", item.getY());
            id.put("points", item.getPoints());
            id.put("active", item.isActive());
            itemList.add(id);
        }
        snapshot.put("items", itemList);

        List<Map<String, Object>> zoneList = new ArrayList<>();
        for (PenaltyZone zone : penaltyZones) {
            Map<String, Object> zd = new HashMap<>();
            zd.put("id", zone.getId());
            zd.put("x", zone.getX());
            zd.put("y", zone.getY());
            zd.put("radius", zone.getRadius());
            zd.put("points", zone.getPoints());
            zd.put("slowMultiplier", zone.getSlowMultiplier());
            zoneList.add(zd);
        }
        snapshot.put("penaltyZones", zoneList);

        return snapshot;
    }

    public void reset() {
        players.clear(); items.clear(); penaltyZones.clear();
        gameTimer = 0; gameRunning = false; localPlayerId = null; isHost = false;
    }

    public String getLocalPlayerId() { return localPlayerId; }
    public void setLocalPlayerId(String v) { this.localPlayerId = v; }
    public String getLocalIp() { return localIp; }
    public void setLocalIp(String v) { this.localIp = v; }
    public int getLocalPort() { return localPort; }
    public void setLocalPort(int v) { this.localPort = v; }
    public String getHostIp() { return hostIp; }
    public void setHostIp(String v) { this.hostIp = v; }
    public int getHostPort() { return hostPort; }
    public void setHostPort(int v) { this.hostPort = v; }
    public boolean isHost() { return isHost; }
    public void setHost(boolean v) { this.isHost = v; }
    public String getPlayerName() { return playerName; }
    public void setPlayerName(String v) { this.playerName = v; }
    public int getExpectedPlayers() { return expectedPlayers; }
    public void setExpectedPlayers(int v) { this.expectedPlayers = v; }
    public Map<String, Player> getPlayers() { return players; }
    public List<CollectibleItem> getItems() { return items; }
    public List<PenaltyZone> getPenaltyZones() { return penaltyZones; }
    public double getGameTimer() { return gameTimer; }
    public void setGameTimer(double v) { this.gameTimer = v; }
    public boolean isGameRunning() { return gameRunning; }
    public void setGameRunning(boolean v) { this.gameRunning = v; }
}
