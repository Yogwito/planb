package com.dino.application.services;

import com.dino.domain.entities.ButtonSwitch;
import com.dino.domain.entities.CollectibleItem;
import com.dino.domain.entities.Door;
import com.dino.domain.entities.ExitZone;
import com.dino.domain.entities.PlatformTile;
import com.dino.domain.entities.Player;
import com.dino.domain.entities.PushBlock;
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
    private final List<PlatformTile> platforms = new ArrayList<>();
    private final List<double[]> spawnPoints = new ArrayList<>();
    private ButtonSwitch buttonSwitch;
    private Door door;
    private ExitZone exitZone;
    private final List<PushBlock> pushBlocks = new ArrayList<>();
    private int roomResetCount;
    private String roomResetReason = "";
    private int currentLevelIndex;
    private int totalLevels;
    private double elapsedTime;
    private boolean gameRunning;
    private volatile long lastSnapshotSeq = -1;
    private long nextSnapshotSeq = 0;
    private final List<CollectibleItem> coins = new ArrayList<>();
    private final Map<String, Long> peerLastSeenMillis = new LinkedHashMap<>();

    public SessionService(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public synchronized void addPlayer(Player player) { players.put(player.getId(), player); }

    public synchronized void removePlayer(String playerId) {
        players.remove(playerId);
        peerAddresses.remove(playerId);
        peerLastSeenMillis.remove(playerId);
    }

    @SuppressWarnings("unchecked")
    public synchronized void updateFromSnapshot(Map<String, Object> data) {
        if (data.containsKey("seq")) {
            long seq = ((Number) data.get("seq")).longValue();
            if (seq <= lastSnapshotSeq) return;
            lastSnapshotSeq = seq;
        }
        if (data.containsKey("elapsedTime")) elapsedTime = ((Number) data.get("elapsedTime")).doubleValue();
        if (data.containsKey("gameRunning")) gameRunning = (Boolean) data.get("gameRunning");
        if (data.containsKey("roomResetCount")) roomResetCount = ((Number) data.get("roomResetCount")).intValue();
        if (data.containsKey("roomResetReason")) roomResetReason = String.valueOf(data.get("roomResetReason"));
        if (data.containsKey("currentLevelIndex")) currentLevelIndex = ((Number) data.get("currentLevelIndex")).intValue();
        if (data.containsKey("totalLevels")) totalLevels = ((Number) data.get("totalLevels")).intValue();

        if (data.containsKey("players")) {
            Set<String> snapshotPlayerIds = new HashSet<>();
            for (Map<String, Object> pd : (List<Map<String, Object>>) data.get("players")) {
                String id = (String) pd.get("id");
                snapshotPlayerIds.add(id);
                Player p = players.computeIfAbsent(id, k -> new Player());
                p.setId(id);
                p.setName((String) pd.getOrDefault("name", p.getName()));
                p.setColor((String) pd.getOrDefault("color", p.getColor()));
                p.setX(((Number) pd.getOrDefault("x", 0)).doubleValue());
                p.setY(((Number) pd.getOrDefault("y", 0)).doubleValue());
                p.setVx(((Number) pd.getOrDefault("vx", 0)).doubleValue());
                p.setVy(((Number) pd.getOrDefault("vy", 0)).doubleValue());
                p.setCoyoteTimer(((Number) pd.getOrDefault("coyoteTimer", 0)).doubleValue());
                p.setGrounded((Boolean) pd.getOrDefault("grounded", false));
                p.setAlive((Boolean) pd.getOrDefault("alive", true));
                p.setAtExit((Boolean) pd.getOrDefault("atExit", false));
                p.setTargetX(((Number) pd.getOrDefault("targetX", p.getX())).doubleValue());
                p.setScore(((Number) pd.getOrDefault("score", 0)).intValue());
                p.setDeaths(((Number) pd.getOrDefault("deaths", 0)).intValue());
                p.setFinishOrder(((Number) pd.getOrDefault("finishOrder", 0)).intValue());
                p.setConnected((Boolean) pd.getOrDefault("connected", true));
                p.setReady((Boolean) pd.getOrDefault("ready", false));
            }
            players.keySet().removeIf(id -> !snapshotPlayerIds.contains(id));
            peerAddresses.keySet().removeIf(id -> !snapshotPlayerIds.contains(id));
        }

        if (data.containsKey("platforms")) {
            platforms.clear();
            for (Map<String, Object> raw : (List<Map<String, Object>>) data.get("platforms")) {
                PlatformTile platform = new PlatformTile();
                platform.setId((String) raw.get("id"));
                platform.setX(((Number) raw.get("x")).doubleValue());
                platform.setY(((Number) raw.get("y")).doubleValue());
                platform.setWidth(((Number) raw.get("width")).doubleValue());
                platform.setHeight(((Number) raw.get("height")).doubleValue());
                platforms.add(platform);
            }
        }

        if (data.containsKey("spawnPoints")) {
            spawnPoints.clear();
            for (Map<String, Object> raw : (List<Map<String, Object>>) data.get("spawnPoints")) {
                spawnPoints.add(new double[]{
                    ((Number) raw.get("x")).doubleValue(),
                    ((Number) raw.get("y")).doubleValue()
                });
            }
        }

        if (data.containsKey("buttonSwitch")) {
            Map<String, Object> raw = (Map<String, Object>) data.get("buttonSwitch");
            ButtonSwitch button = new ButtonSwitch();
            button.setId((String) raw.get("id"));
            button.setX(((Number) raw.get("x")).doubleValue());
            button.setY(((Number) raw.get("y")).doubleValue());
            button.setWidth(((Number) raw.get("width")).doubleValue());
            button.setHeight(((Number) raw.get("height")).doubleValue());
            button.setPressed((Boolean) raw.getOrDefault("pressed", false));
            buttonSwitch = button;
        }

        if (data.containsKey("door")) {
            Map<String, Object> raw = (Map<String, Object>) data.get("door");
            Door value = new Door();
            value.setId((String) raw.get("id"));
            value.setX(((Number) raw.get("x")).doubleValue());
            value.setY(((Number) raw.get("y")).doubleValue());
            value.setWidth(((Number) raw.get("width")).doubleValue());
            value.setHeight(((Number) raw.get("height")).doubleValue());
            value.setOpen((Boolean) raw.getOrDefault("open", false));
            door = value;
        }

        if (data.containsKey("exitZone")) {
            Map<String, Object> raw = (Map<String, Object>) data.get("exitZone");
            ExitZone value = new ExitZone();
            value.setX(((Number) raw.get("x")).doubleValue());
            value.setY(((Number) raw.get("y")).doubleValue());
            value.setWidth(((Number) raw.get("width")).doubleValue());
            value.setHeight(((Number) raw.get("height")).doubleValue());
            exitZone = value;
        }

        if (data.containsKey("pushBlocks")) {
            pushBlocks.clear();
            for (Map<String, Object> raw : (List<Map<String, Object>>) data.get("pushBlocks")) {
                PushBlock block = new PushBlock();
                block.setId((String) raw.get("id"));
                block.setX(((Number) raw.get("x")).doubleValue());
                block.setY(((Number) raw.get("y")).doubleValue());
                block.setWidth(((Number) raw.get("width")).doubleValue());
                block.setHeight(((Number) raw.get("height")).doubleValue());
                block.setVx(((Number) raw.getOrDefault("vx", 0)).doubleValue());
                block.setVy(((Number) raw.getOrDefault("vy", 0)).doubleValue());
                block.setHomeX(((Number) raw.getOrDefault("homeX", block.getX())).doubleValue());
                block.setHomeY(((Number) raw.getOrDefault("homeY", block.getY())).doubleValue());
                pushBlocks.add(block);
            }
        }

        if (data.containsKey("coins")) {
            coins.clear();
            for (Map<String, Object> raw : (List<Map<String, Object>>) data.get("coins")) {
                CollectibleItem c = new CollectibleItem(
                    (String) raw.get("id"),
                    ((Number) raw.get("x")).doubleValue(),
                    ((Number) raw.get("y")).doubleValue(),
                    ((Number) raw.get("points")).intValue()
                );
                c.setActive((Boolean) raw.getOrDefault("active", true));
                coins.add(c);
            }
        }

        eventBus.publish(EventNames.SNAPSHOT_RECEIVED, data);
    }

    public synchronized Map<String, Object> getSnapshotData() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("seq", ++nextSnapshotSeq);
        snapshot.put("elapsedTime", elapsedTime);
        snapshot.put("gameRunning", gameRunning);
        snapshot.put("roomResetCount", roomResetCount);
        snapshot.put("roomResetReason", roomResetReason);
        snapshot.put("currentLevelIndex", currentLevelIndex);
        snapshot.put("totalLevels", totalLevels);

        List<Map<String, Object>> playerList = new ArrayList<>();
        for (Player p : players.values()) {
            Map<String, Object> pd = new HashMap<>();
            pd.put("id", p.getId());
            pd.put("name", p.getName());
            pd.put("color", p.getColor());
            pd.put("x", p.getX());
            pd.put("y", p.getY());
            pd.put("vx", p.getVx());
            pd.put("vy", p.getVy());
            pd.put("coyoteTimer", p.getCoyoteTimer());
            pd.put("grounded", p.isGrounded());
            pd.put("alive", p.isAlive());
            pd.put("atExit", p.isAtExit());
            pd.put("targetX", p.getTargetX());
            pd.put("score", p.getScore());
            pd.put("deaths", p.getDeaths());
            pd.put("finishOrder", p.getFinishOrder());
            pd.put("connected", p.isConnected());
            pd.put("ready", p.isReady());
            playerList.add(pd);
        }
        snapshot.put("players", playerList);

        List<Map<String, Object>> platformList = new ArrayList<>();
        for (PlatformTile platform : platforms) {
            Map<String, Object> raw = new HashMap<>();
            raw.put("id", platform.getId());
            raw.put("x", platform.getX());
            raw.put("y", platform.getY());
            raw.put("width", platform.getWidth());
            raw.put("height", platform.getHeight());
            platformList.add(raw);
        }
        snapshot.put("platforms", platformList);

        List<Map<String, Object>> spawnList = new ArrayList<>();
        for (double[] spawn : spawnPoints) {
            Map<String, Object> raw = new HashMap<>();
            raw.put("x", spawn[0]);
            raw.put("y", spawn[1]);
            spawnList.add(raw);
        }
        snapshot.put("spawnPoints", spawnList);

        if (buttonSwitch != null) {
            Map<String, Object> raw = new HashMap<>();
            raw.put("id", buttonSwitch.getId());
            raw.put("x", buttonSwitch.getX());
            raw.put("y", buttonSwitch.getY());
            raw.put("width", buttonSwitch.getWidth());
            raw.put("height", buttonSwitch.getHeight());
            raw.put("pressed", buttonSwitch.isPressed());
            snapshot.put("buttonSwitch", raw);
        }

        if (door != null) {
            Map<String, Object> raw = new HashMap<>();
            raw.put("id", door.getId());
            raw.put("x", door.getX());
            raw.put("y", door.getY());
            raw.put("width", door.getWidth());
            raw.put("height", door.getHeight());
            raw.put("open", door.isOpen());
            snapshot.put("door", raw);
        }

        if (exitZone != null) {
            Map<String, Object> raw = new HashMap<>();
            raw.put("x", exitZone.getX());
            raw.put("y", exitZone.getY());
            raw.put("width", exitZone.getWidth());
            raw.put("height", exitZone.getHeight());
            snapshot.put("exitZone", raw);
        }

        List<Map<String, Object>> blockList = new ArrayList<>();
        for (PushBlock block : pushBlocks) {
            Map<String, Object> raw = new HashMap<>();
            raw.put("id", block.getId());
            raw.put("x", block.getX());
            raw.put("y", block.getY());
            raw.put("width", block.getWidth());
            raw.put("height", block.getHeight());
            raw.put("vx", block.getVx());
            raw.put("vy", block.getVy());
            raw.put("homeX", block.getHomeX());
            raw.put("homeY", block.getHomeY());
            blockList.add(raw);
        }
        snapshot.put("pushBlocks", blockList);

        List<Map<String, Object>> coinList = new ArrayList<>();
        for (CollectibleItem c : coins) {
            Map<String, Object> raw = new HashMap<>();
            raw.put("id", c.getId());
            raw.put("x", c.getX());
            raw.put("y", c.getY());
            raw.put("points", c.getPoints());
            raw.put("active", c.isActive());
            coinList.add(raw);
        }
        snapshot.put("coins", coinList);

        return snapshot;
    }

    public List<CollectibleItem> getCoins() { return coins; }

    public synchronized List<CollectibleItem> getCoinsSnapshot() {
        List<CollectibleItem> snapshot = new ArrayList<>();
        for (CollectibleItem c : coins) {
            CollectibleItem copy = new CollectibleItem(c.getId(), c.getX(), c.getY(), c.getPoints());
            copy.setActive(c.isActive());
            snapshot.add(copy);
        }
        return snapshot;
    }

    public synchronized void reset() {
        players.clear();
        peerAddresses.clear();
        platforms.clear();
        spawnPoints.clear();
        pushBlocks.clear();
        coins.clear();
        buttonSwitch = null;
        door = null;
        exitZone = null;
        roomResetCount = 0;
        roomResetReason = "";
        currentLevelIndex = 0;
        totalLevels = 0;
        elapsedTime = 0;
        gameRunning = false;
        lastSnapshotSeq = -1;
        nextSnapshotSeq = 0;
        localPlayerId = null;
        isHost = false;
        peerLastSeenMillis.clear();
    }

    public synchronized void registerPeerAddress(String playerId, InetSocketAddress address) {
        if (playerId != null && address != null) {
            peerAddresses.put(playerId, address);
            peerLastSeenMillis.put(playerId, System.currentTimeMillis());
        }
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
        if (connected) {
            peerLastSeenMillis.put(playerId, System.currentTimeMillis());
        } else {
            peerLastSeenMillis.remove(playerId);
        }
    }

    public synchronized void removePeerAddress(String playerId) {
        if (playerId != null) {
            peerAddresses.remove(playerId);
            peerLastSeenMillis.remove(playerId);
        }
    }

    public synchronized void markPeerSeen(String playerId) {
        if (playerId != null) peerLastSeenMillis.put(playerId, System.currentTimeMillis());
    }

    public synchronized Long getPeerAgeMillis(String playerId) {
        Long lastSeen = peerLastSeenMillis.get(playerId);
        if (lastSeen == null) return null;
        return Math.max(0L, System.currentTimeMillis() - lastSeen);
    }

    public synchronized List<String> expireInactivePeers(long timeoutMillis) {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (Player player : players.values()) {
            if (!player.isConnected()) continue;
            if (Objects.equals(player.getId(), localPlayerId) && isHost) continue;
            Long lastSeen = peerLastSeenMillis.get(player.getId());
            if (lastSeen == null) continue;
            if (now - lastSeen > timeoutMillis) {
                player.setConnected(false);
                peerAddresses.remove(player.getId());
                expired.add(player.getId());
            }
        }
        expired.forEach(peerLastSeenMillis::remove);
        return expired;
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
            copy.setVx(player.getVx());
            copy.setVy(player.getVy());
            copy.setCoyoteTimer(player.getCoyoteTimer());
            copy.setGrounded(player.isGrounded());
            copy.setAlive(player.isAlive());
            copy.setAtExit(player.isAtExit());
            copy.setTargetX(player.getTargetX());
            copy.setScore(player.getScore());
            copy.setDeaths(player.getDeaths());
            copy.setFinishOrder(player.getFinishOrder());
            copy.setConnected(player.isConnected());
            copy.setReady(player.isReady());
            snapshot.add(copy);
        }
        return snapshot;
    }

    public synchronized List<PlatformTile> getPlatformsSnapshot() {
        List<PlatformTile> snapshot = new ArrayList<>();
        for (PlatformTile platform : platforms) {
            snapshot.add(new PlatformTile(platform.getId(), platform.getX(), platform.getY(), platform.getWidth(), platform.getHeight()));
        }
        return snapshot;
    }

    public synchronized List<double[]> getSpawnPointsSnapshot() {
        List<double[]> snapshot = new ArrayList<>();
        for (double[] spawn : spawnPoints) snapshot.add(new double[]{spawn[0], spawn[1]});
        return snapshot;
    }

    public synchronized ButtonSwitch getButtonSwitchSnapshot() {
        if (buttonSwitch == null) return null;
        ButtonSwitch copy = new ButtonSwitch(buttonSwitch.getId(), buttonSwitch.getX(), buttonSwitch.getY(), buttonSwitch.getWidth(), buttonSwitch.getHeight());
        copy.setPressed(buttonSwitch.isPressed());
        return copy;
    }

    public synchronized Door getDoorSnapshot() {
        if (door == null) return null;
        Door copy = new Door(door.getId(), door.getX(), door.getY(), door.getWidth(), door.getHeight());
        copy.setOpen(door.isOpen());
        return copy;
    }

    public synchronized ExitZone getExitZoneSnapshot() {
        if (exitZone == null) return null;
        return new ExitZone(exitZone.getX(), exitZone.getY(), exitZone.getWidth(), exitZone.getHeight());
    }

    public synchronized List<PushBlock> getPushBlocksSnapshot() {
        List<PushBlock> snapshot = new ArrayList<>();
        for (PushBlock block : pushBlocks) {
            PushBlock copy = new PushBlock(block.getId(), block.getX(), block.getY(), block.getWidth(), block.getHeight());
            copy.setVx(block.getVx());
            copy.setVy(block.getVy());
            copy.setHomeX(block.getHomeX());
            copy.setHomeY(block.getHomeY());
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
    public List<PlatformTile> getPlatforms() { return platforms; }
    public List<double[]> getSpawnPoints() { return spawnPoints; }
    public ButtonSwitch getButtonSwitch() { return buttonSwitch; }
    public void setButtonSwitch(ButtonSwitch buttonSwitch) { this.buttonSwitch = buttonSwitch; }
    public Door getDoor() { return door; }
    public void setDoor(Door door) { this.door = door; }
    public ExitZone getExitZone() { return exitZone; }
    public void setExitZone(ExitZone exitZone) { this.exitZone = exitZone; }
    public List<PushBlock> getPushBlocks() { return pushBlocks; }
    public synchronized int getRoomResetCount() { return roomResetCount; }
    public synchronized void setRoomResetCount(int roomResetCount) { this.roomResetCount = roomResetCount; }
    public synchronized String getRoomResetReason() { return roomResetReason; }
    public synchronized void setRoomResetReason(String roomResetReason) { this.roomResetReason = roomResetReason; }
    public synchronized int getCurrentLevelIndex() { return currentLevelIndex; }
    public synchronized void setCurrentLevelIndex(int currentLevelIndex) { this.currentLevelIndex = currentLevelIndex; }
    public synchronized int getTotalLevels() { return totalLevels; }
    public synchronized void setTotalLevels(int totalLevels) { this.totalLevels = totalLevels; }
    public synchronized double getElapsedTime() { return elapsedTime; }
    public synchronized void setElapsedTime(double elapsedTime) { this.elapsedTime = elapsedTime; }
    public synchronized boolean isGameRunning() { return gameRunning; }
    public synchronized void setGameRunning(boolean gameRunning) { this.gameRunning = gameRunning; }
}
