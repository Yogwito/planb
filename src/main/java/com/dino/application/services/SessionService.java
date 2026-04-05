package com.dino.application.services;

import com.dino.domain.entities.CollectibleItem;
import com.dino.domain.entities.PenaltyZone;
import com.dino.domain.entities.Player;
import com.dino.domain.events.EventNames;

import java.net.InetSocketAddress;
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
    private final Map<String, InetSocketAddress> peerAddresses = new LinkedHashMap<>();
    private final List<CollectibleItem> items = new ArrayList<>();
    private final List<PenaltyZone> penaltyZones = new ArrayList<>();
    private double gameTimer;
    private boolean gameRunning;

    public SessionService(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public synchronized void addPlayer(Player player) { players.put(player.getId(), player); }
    public synchronized void removePlayer(String playerId) {
        players.remove(playerId);
        peerAddresses.remove(playerId);
    }

    @SuppressWarnings("unchecked")
    public synchronized void updateFromSnapshot(Map<String, Object> data) {
        if (data.containsKey("gameTimer"))   gameTimer   = ((Number) data.get("gameTimer")).doubleValue();
        if (data.containsKey("gameRunning")) gameRunning = (Boolean) data.get("gameRunning");

        if (data.containsKey("players")) {
            Set<String> snapshotPlayerIds = new HashSet<>();
            for (Map<String, Object> pd : (List<Map<String, Object>>) data.get("players")) {
                String id = (String) pd.get("id");
                snapshotPlayerIds.add(id);
                Player p = players.computeIfAbsent(id, k -> new Player());
                p.setId(id);
                if (pd.get("name")  != null) p.setName((String) pd.get("name"));
                if (pd.get("color") != null) p.setColor((String) pd.get("color"));
                if (pd.get("x")     != null) p.setX(((Number) pd.get("x")).doubleValue());
                if (pd.get("y")     != null) p.setY(((Number) pd.get("y")).doubleValue());
                if (pd.get("mass")  != null) p.setMass(((Number) pd.get("mass")).doubleValue());
                p.setConnected((Boolean) pd.getOrDefault("connected", true));
                p.setReady((Boolean) pd.getOrDefault("ready", false));
            }
            players.keySet().removeIf(id -> !snapshotPlayerIds.contains(id));
            peerAddresses.keySet().removeIf(id -> !snapshotPlayerIds.contains(id));
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
                zone.setTriggerMass(((Number) zd.getOrDefault("triggerMass", 90.0)).doubleValue());
                zone.setBurstRatio(((Number) zd.getOrDefault("burstRatio", 0.35)).doubleValue());
                penaltyZones.add(zone);
            }
        }

        eventBus.publish(EventNames.SNAPSHOT_RECEIVED, data);
    }

    public synchronized Map<String, Object> getSnapshotData() {
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
            pd.put("mass", p.getMass());
            pd.put("score", p.getScore());
            pd.put("connected", p.isConnected());
            pd.put("ready", p.isReady());
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
            zd.put("triggerMass", zone.getTriggerMass());
            zd.put("burstRatio", zone.getBurstRatio());
            zoneList.add(zd);
        }
        snapshot.put("penaltyZones", zoneList);

        return snapshot;
    }

    public synchronized void reset() {
        players.clear(); peerAddresses.clear(); items.clear(); penaltyZones.clear();
        gameTimer = 0; gameRunning = false; localPlayerId = null; isHost = false;
    }

    public synchronized void registerPeerAddress(String playerId, InetSocketAddress address) {
        if (playerId != null && address != null) peerAddresses.put(playerId, address);
    }

    public synchronized List<InetSocketAddress> getRemotePeerAddresses() {
        List<InetSocketAddress> remotes = new ArrayList<>();
        for (Map.Entry<String, InetSocketAddress> entry : peerAddresses.entrySet()) {
            if (!Objects.equals(entry.getKey(), localPlayerId)) remotes.add(entry.getValue());
        }
        return remotes;
    }

    public synchronized void markPlayerReady(String playerId, boolean ready) {
        Player player = players.get(playerId);
        if (player != null) player.setReady(ready);
    }

    public synchronized void markPlayerConnected(String playerId, boolean connected) {
        Player player = players.get(playerId);
        if (player != null) player.setConnected(connected);
    }

    public synchronized void removePeerAddress(String playerId) {
        if (playerId != null) peerAddresses.remove(playerId);
    }

    public synchronized List<Player> getPlayersSnapshot() {
        List<Player> snapshot = new ArrayList<>();
        for (Player player : players.values()) {
            Player copy = new Player();
            copy.setId(player.getId());
            copy.setName(player.getName());
            copy.setColor(player.getColor());
            copy.setX(player.getX());
            copy.setY(player.getY());
            copy.setMass(player.getMass());
            copy.setConnected(player.isConnected());
            copy.setReady(player.isReady());
            snapshot.add(copy);
        }
        return snapshot;
    }

    public synchronized List<CollectibleItem> getItemsSnapshot() {
        List<CollectibleItem> snapshot = new ArrayList<>();
        for (CollectibleItem item : items) {
            CollectibleItem copy = new CollectibleItem();
            copy.setId(item.getId());
            copy.setX(item.getX());
            copy.setY(item.getY());
            copy.setPoints(item.getPoints());
            copy.setActive(item.isActive());
            snapshot.add(copy);
        }
        return snapshot;
    }

    public synchronized List<PenaltyZone> getPenaltyZonesSnapshot() {
        List<PenaltyZone> snapshot = new ArrayList<>();
        for (PenaltyZone zone : penaltyZones) {
            PenaltyZone copy = new PenaltyZone();
            copy.setId(zone.getId());
            copy.setX(zone.getX());
            copy.setY(zone.getY());
            copy.setRadius(zone.getRadius());
            copy.setTriggerMass(zone.getTriggerMass());
            copy.setBurstRatio(zone.getBurstRatio());
            snapshot.add(copy);
        }
        return snapshot;
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
    public synchronized double getGameTimer() { return gameTimer; }
    public synchronized void setGameTimer(double v) { this.gameTimer = v; }
    public synchronized boolean isGameRunning() { return gameRunning; }
    public synchronized void setGameRunning(boolean v) { this.gameRunning = v; }
}
