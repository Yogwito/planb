package com.dino.presentation.controllers;

import com.dino.MainApp;
import com.dino.config.GameConfig;
import com.dino.domain.entities.ButtonSwitch;
import com.dino.domain.entities.CollectibleItem;
import com.dino.domain.entities.Door;
import com.dino.domain.entities.ExitZone;
import com.dino.domain.entities.PlatformTile;
import com.dino.domain.entities.Player;
import com.dino.domain.entities.PushBlock;
import com.dino.domain.events.EventNames;
import com.dino.infrastructure.serialization.MessageSerializer;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Comparator;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;

public class GameController implements Initializable {
    @FXML private Canvas arenaCanvas;
    @FXML private Label timerLabel;
    @FXML private Label levelLabel;
    @FXML private Label roomStatusLabel;
    @FXML private Label threadLabel;
    @FXML private Label networkLabel;
    @FXML private Label feedbackLabel;
    @FXML private ListView<String> playersList;
    @FXML private ListView<String> eventLog;

    private final MessageSerializer serializer = new MessageSerializer();
    private AnimationTimer gameLoop;
    private long lastNano;
    private double snapshotTimer;
    private double currentZoom = GameConfig.BASE_ZOOM;
    private double cameraX;
    private double cameraY;
    private double feedbackTimer;
    private double inputResendTimer;
    private double heartbeatTimer;
    private Double pendingTargetX;
    private int pendingJumpRepeats;
    private boolean leftPressed;
    private boolean rightPressed;
    private boolean gameOverHandled;
    private double timeSinceLastSnapshot;
    private double lastSnapshotAgeForRender;
    private String pendingCriticalType;
    private int pendingCriticalVersion;
    private double pendingCriticalAge;
    private final Set<String> criticalAckedPeers = new HashSet<>();
    private final Map<String, Integer> peerWarningCounts = new HashMap<>();
    private final Set<String> stalePeers = new HashSet<>();
    private final Map<String, RenderState> renderStates = new HashMap<>();
    private final Map<String, Deque<SnapshotSample>> snapshotBuffers = new HashMap<>();
    private final List<Particle> particles = new ArrayList<>();

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        MainApp.eventBus.subscribe(EventNames.SNAPSHOT_RECEIVED, e -> {
            timeSinceLastSnapshot = 0;
            captureSnapshotSamples();
            Platform.runLater(this::refreshUI);
        });
        MainApp.eventBus.subscribe(EventNames.ROOM_RESET, e -> Platform.runLater(() ->
            {
                if (MainApp.sessionService.isHost()) {
                    armCriticalSnapshot("ROOM_RESET", MainApp.sessionService.getRoomResetCount());
                }
                showFeedback("Sala reiniciada", "#ffadad");
                spawnBurst(490, 260, Color.web("#ffadad"), 14);
            }));
        MainApp.eventBus.subscribe(EventNames.BUTTON_STATE_CHANGED, e -> Platform.runLater(() ->
            {
                showFeedback(Boolean.TRUE.equals(e.get("pressed")) ? "Boton activado" : "Boton liberado", "#ffe08a");
                ButtonSwitch button = MainApp.sessionService.getButtonSwitchSnapshot();
                if (button != null) {
                    spawnBurst(worldToScreenX(button.getX() + button.getWidth() / 2.0, arenaCanvas.getWidth() / getViewportWorldWidth()),
                        worldToScreenY(button.getY(), arenaCanvas.getHeight() / getViewportWorldHeight()), Color.web("#ffe08a"), 10);
                }
            }));
        MainApp.eventBus.subscribe(EventNames.LEVEL_ADVANCED, e -> Platform.runLater(() ->
            {
                if (MainApp.sessionService.isHost()) {
                    armCriticalSnapshot("LEVEL_ADVANCED", MainApp.sessionService.getCurrentLevelIndex());
                }
                showFeedback("Nivel " + (((Number) e.getOrDefault("levelIndex", 0)).intValue() + 1), "#8be9fd");
                spawnBurst(arenaCanvas.getWidth() * 0.5, 200, Color.web("#8be9fd"), 22);
            }));
        MainApp.eventBus.subscribe(EventNames.PLAYER_JUMPED, e -> onJumpEvent((String) e.get("playerId")));
        MainApp.eventBus.subscribe(EventNames.PLAYER_COLLIDED, this::onCollisionEvent);
        MainApp.eventBus.subscribe(EventNames.THREAD_STRETCHED, this::onThreadEvent);
        MainApp.eventBus.subscribe(EventNames.PLAYER_REACHED_EXIT, e -> onReachedExitEvent((String) e.get("playerId")));
        MainApp.eventBus.subscribe(EventNames.SCORE_CHANGED, e -> onScoreChangedEvent(e));
        MainApp.eventBus.subscribe(EventNames.GAME_OVER, e -> {
            if (gameOverHandled) return;
            gameOverHandled = true;
            if (MainApp.sessionService.isHost()) broadcastGameOver();
            Platform.runLater(this::onGameOver);
        });
        MainApp.eventBus.subscribe(EventNames.COIN_COLLECTED, e -> Platform.runLater(() -> {
            double wx = ((Number) e.get("x")).doubleValue() + GameConfig.COIN_SIZE / 2;
            double wy = ((Number) e.get("y")).doubleValue() + GameConfig.COIN_SIZE / 2;
            int pts = ((Number) e.get("points")).intValue();
            double sx = arenaCanvas.getWidth() / getViewportWorldWidth();
            double sy = arenaCanvas.getHeight() / getViewportWorldHeight();
            Color burst = pts >= GameConfig.SCORE_COIN_LARGE ? Color.web("#ffa500") : Color.web("#ffd166");
            spawnBurst(worldToScreenX(wx, sx), worldToScreenY(wy, sy), burst, pts >= GameConfig.SCORE_COIN_LARGE ? 12 : 7);
            showFeedback("+" + pts + " moneda!", pts >= GameConfig.SCORE_COIN_LARGE ? "#ffa500" : "#ffd166");
        }));
        MainApp.eventBus.subscribe(EventNames.PLAYER_DISCONNECTED, e -> Platform.runLater(() -> {
            String playerId = String.valueOf(e.getOrDefault("playerId", "?"));
            peerWarningCounts.merge(playerId, 1, Integer::sum);
            showFeedback("Conexion perdida: " + playerId, "#ff9f1c");
            spawnBurst(arenaCanvas.getWidth() * 0.55, 140, Color.web("#ff9f1c"), 12);
        }));

        feedbackLabel.setVisible(false);
        installKeyboardHandlers();
        startGameLoop();
    }

    private void installKeyboardHandlers() {
        Platform.runLater(() -> {
            Scene scene = arenaCanvas.getScene();
            if (scene == null) return;
            scene.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.LEFT) {
                    leftPressed = true;
                    updateMovementTarget(false);
                } else if (event.getCode() == KeyCode.RIGHT) {
                    rightPressed = true;
                    updateMovementTarget(false);
                } else if (event.getCode() == KeyCode.SPACE) {
                    updateMovementTarget(true);
                }
            });
            scene.setOnKeyReleased(event -> {
                if (event.getCode() == KeyCode.LEFT) {
                    leftPressed = false;
                    updateMovementTarget(false);
                } else if (event.getCode() == KeyCode.RIGHT) {
                    rightPressed = false;
                    updateMovementTarget(false);
                }
            });
            arenaCanvas.requestFocus();
            if (scene.getRoot() != null) {
                scene.getRoot().requestFocus();
            }
            arenaCanvas.setOnMousePressed(e -> {
                double sx = arenaCanvas.getWidth() / getViewportWorldWidth();
                double sy = arenaCanvas.getHeight() / getViewportWorldHeight();
                double worldX = e.getX() / sx + cameraX;
                double worldY = e.getY() / sy + cameraY;
                Player local = getLocalPlayerSnapshot();
                boolean upwardClick = local != null
                    && (local.getCenterY() - worldY) > GameConfig.MOUSE_JUMP_VERTICAL_THRESHOLD;
                boolean jump = e.getButton() == MouseButton.SECONDARY || upwardClick;
                sendInput(worldX, jump);
            });
        });
    }

    private void updateMovementTarget(boolean jumpRequested) {
        Player local = getLocalPlayerSnapshot();
        if (local == null) return;

        double targetX = local.getCenterX();
        if (leftPressed && !rightPressed) {
            targetX = -1000;
        } else if (rightPressed && !leftPressed) {
            targetX = GameConfig.LEVEL_WIDTH + 1000;
        }
        sendInput(targetX, jumpRequested);
    }

    private void startGameLoop() {
        gameLoop = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (lastNano == 0) {
                    lastNano = now;
                    return;
                }
                double dt = (now - lastNano) / 1_000_000_000.0;
                lastNano = now;
                timeSinceLastSnapshot += dt;

                if (MainApp.udpPeer != null && MainApp.udpPeer.isBound()) pollIncomingMessages();
                if (!MainApp.sessionService.isHost()) resendPendingInput(dt);
                if (!MainApp.sessionService.isHost()) sendHeartbeatIfDue(dt);
                if (MainApp.sessionService.isHost()) expireInactivePeersIfNeeded();

                if (MainApp.sessionService.isHost() && MainApp.hostMatchService != null) {
                    MainApp.hostMatchService.tick(dt);
                    updateCriticalAckWindow(dt);
                    snapshotTimer += dt;
                    if (snapshotTimer >= (1.0 / GameConfig.SNAPSHOT_RATE_HZ)) {
                        snapshotTimer = 0;
                        Map<String, Object> snapshot = MainApp.sessionService.getSnapshotData();
                        attachCriticalMetadata(snapshot);
                        snapshot.put("type", MessageSerializer.SNAPSHOT);
                        MainApp.sessionService.updateFromSnapshot(snapshot);
                        MainApp.udpPeer.broadcast(snapshot, MainApp.sessionService.getRemotePeerAddresses());
                    }
                }

                lastSnapshotAgeForRender = timeSinceLastSnapshot;
                updateCamera(dt);
                updateFeedback(dt);
                updateParticles(dt);
                render();
                updateTimer();
            }
        };
        gameLoop.start();
    }

    private void sendInput(double targetX, boolean jumpRequested) {
        pendingTargetX = targetX;
        if (jumpRequested) pendingJumpRepeats = Math.max(pendingJumpRepeats, 2);
        if (MainApp.sessionService.isHost()) {
            if (MainApp.hostMatchService != null) {
                MainApp.hostMatchService.handleInput(MainApp.sessionService.getLocalPlayerId(), targetX, jumpRequested);
            }
            return;
        }

        sendInputPacket(targetX, jumpRequested);
    }

    private void resendPendingInput(double dt) {
        if (pendingTargetX == null) return;
        inputResendTimer += dt;
        if (inputResendTimer < GameConfig.CLIENT_INPUT_RESEND_SECONDS) return;
        inputResendTimer = 0;

        boolean jump = pendingJumpRepeats > 0;
        if (jump) pendingJumpRepeats--;
        sendInputPacket(pendingTargetX, jump);
    }

    private void sendHeartbeatIfDue(double dt) {
        heartbeatTimer += dt;
        if (heartbeatTimer < GameConfig.CLIENT_HEARTBEAT_SECONDS) return;
        heartbeatTimer = 0;
        try {
            Map<String, Object> msg = serializer.build(
                MessageSerializer.HEARTBEAT,
                "playerId", MainApp.sessionService.getLocalPlayerId()
            );
            MainApp.udpPeer.send(msg,
                InetAddress.getByName(MainApp.sessionService.getHostIp()),
                MainApp.sessionService.getHostPort());
        } catch (Exception e) {
            System.err.println("[GameController] Heartbeat error: " + e.getMessage());
        }
    }

    private void expireInactivePeersIfNeeded() {
        List<String> expired = MainApp.sessionService.expireInactivePeers((long) (GameConfig.HOST_PEER_TIMEOUT_SECONDS * 1000));
        if (expired.isEmpty()) return;
        for (String playerId : expired) {
            MainApp.eventBus.publish(EventNames.PLAYER_DISCONNECTED, Map.of("playerId", playerId));
        }
        Map<String, Object> snapshot = MainApp.sessionService.getSnapshotData();
        attachCriticalMetadata(snapshot);
        snapshot.put("type", MessageSerializer.SNAPSHOT);
        MainApp.sessionService.updateFromSnapshot(snapshot);
        MainApp.udpPeer.broadcast(snapshot, MainApp.sessionService.getRemotePeerAddresses());
    }

    private void sendInputPacket(double targetX, boolean jumpRequested) {
        try {
            Map<String, Object> msg = serializer.build(
                MessageSerializer.INPUT,
                "playerId", MainApp.sessionService.getLocalPlayerId(),
                "targetX", targetX,
                "jump", jumpRequested
            );
            MainApp.udpPeer.send(msg,
                InetAddress.getByName(MainApp.sessionService.getHostIp()),
                MainApp.sessionService.getHostPort());
        } catch (Exception e) {
            System.err.println("[GameController] Input error: " + e.getMessage());
        }
    }

    private void pollIncomingMessages() {
        int limit = 20;
        while (limit-- > 0) {
            var received = MainApp.udpPeer.receive();
            if (received.isEmpty()) return;
            handleIncomingMessage(received.get().getKey(), received.get().getValue());
        }
    }

    private void handleIncomingMessage(Map<String, Object> msg, InetSocketAddress sender) {
        Object type = msg.get("type");
        if (!(type instanceof String messageType)) return;

        if (MainApp.sessionService.isHost()) {
            if (MessageSerializer.INPUT.equals(messageType) && MainApp.hostMatchService != null) {
                String playerId = (String) msg.get("playerId");
                Number targetX = (Number) msg.get("targetX");
                boolean jump = Boolean.TRUE.equals(msg.get("jump"));
                if (playerId != null && targetX != null) {
                    MainApp.sessionService.registerPeerAddress(playerId, sender);
                    MainApp.sessionService.markPeerSeen(playerId);
                    MainApp.sessionService.markPlayerConnected(playerId, true);
                    MainApp.hostMatchService.handleInput(playerId, targetX.doubleValue(), jump);
                }
            } else if (MessageSerializer.HEARTBEAT.equals(messageType)) {
                String playerId = (String) msg.get("playerId");
                if (playerId != null) {
                    MainApp.sessionService.registerPeerAddress(playerId, sender);
                    MainApp.sessionService.markPeerSeen(playerId);
                    MainApp.sessionService.markPlayerConnected(playerId, true);
                }
            } else if (MessageSerializer.ACK.equals(messageType)) {
                String playerId = (String) msg.get("playerId");
                String criticalType = (String) msg.get("criticalType");
                Number criticalVersion = (Number) msg.get("criticalVersion");
                if (playerId != null && criticalType != null && criticalVersion != null
                    && criticalType.equals(pendingCriticalType)
                    && criticalVersion.intValue() == pendingCriticalVersion) {
                    criticalAckedPeers.add(playerId);
                }
            } else if (MessageSerializer.DISCONNECT.equals(messageType)) {
                String playerId = (String) msg.get("playerId");
                MainApp.sessionService.markPlayerConnected(playerId, false);
                MainApp.sessionService.removePeerAddress(playerId);
                Map<String, Object> snapshot = MainApp.sessionService.getSnapshotData();
                snapshot.put("type", MessageSerializer.SNAPSHOT);
                MainApp.sessionService.updateFromSnapshot(snapshot);
                MainApp.udpPeer.broadcast(snapshot, MainApp.sessionService.getRemotePeerAddresses());
            }
            return;
        }

        if (MessageSerializer.SNAPSHOT.equals(messageType) || MessageSerializer.GAME_OVER.equals(messageType)) {
            MainApp.sessionService.updateFromSnapshot(msg);
            acknowledgeCriticalSnapshotIfNeeded(msg);
            if (MessageSerializer.GAME_OVER.equals(messageType) && !gameOverHandled) {
                gameOverHandled = true;
                Platform.runLater(this::onGameOver);
            }
        }
    }

    private void broadcastGameOver() {
        if (MainApp.udpPeer == null || !MainApp.udpPeer.isBound()) return;
        Map<String, Object> payload = MainApp.sessionService.getSnapshotData();
        payload.put("type", MessageSerializer.GAME_OVER);
        MainApp.udpPeer.broadcastBurst(payload, MainApp.sessionService.getRemotePeerAddresses(), 8, 60);
    }

    private void render() {
        double width = arenaCanvas.getWidth();
        double height = arenaCanvas.getHeight();
        double viewportW = getViewportWorldWidth();
        double viewportH = getViewportWorldHeight();
        double scaleX = width / viewportW;
        double scaleY = height / viewportH;

        GraphicsContext gc = arenaCanvas.getGraphicsContext2D();
        drawBackdrop(gc, width, height);
        drawBackgroundGrid(gc, width, height, scaleX, scaleY);
        drawBackdropShapes(gc, width, height, scaleX, scaleY);

        ExitZone exitZone = MainApp.sessionService.getExitZoneSnapshot();
        if (exitZone != null) {
            double x = worldToScreenX(exitZone.getX(), scaleX);
            double y = worldToScreenY(exitZone.getY(), scaleY);
            gc.setFill(Color.web("#1f3b5b"));
            gc.fillRect(x, y, exitZone.getWidth() * scaleX, exitZone.getHeight() * scaleY);
            gc.setFill(Color.web("#4cc9f055"));
            gc.fillRoundRect(x + 10, y + 10, exitZone.getWidth() * scaleX - 20, exitZone.getHeight() * scaleY - 20, 16, 16);
            gc.setStroke(Color.web("#87e0ff"));
            gc.setLineWidth(4);
            gc.strokeRect(x, y, exitZone.getWidth() * scaleX, exitZone.getHeight() * scaleY);
            gc.setFill(Color.WHITE);
            gc.setFont(Font.font("Arial", FontWeight.BOLD, 15));
            gc.fillText("SALIDA", x + 22, y + 26);
        }

        int lvl = MainApp.sessionService.getCurrentLevelIndex();
        String[] sueloColor = {"#1a1a2e", "#3d1500", "#050508"};
        String[] bordeColor = {"#4a4a6a", "#7a3010", "#1a0030"};
        double sueloY = worldToScreenY(800, arenaCanvas.getHeight() / getViewportWorldHeight());
        gc.setFill(Color.web(sueloColor[Math.min(lvl, 2)]));
        gc.fillRect(0, sueloY, arenaCanvas.getWidth(), arenaCanvas.getHeight() - sueloY);
        gc.setStroke(Color.web(bordeColor[Math.min(lvl, 2)]));
        gc.setLineWidth(3);
        gc.strokeLine(0, sueloY, arenaCanvas.getWidth(), sueloY);

        for (PlatformTile platform : MainApp.sessionService.getPlatformsSnapshot()) {
            double x = worldToScreenX(platform.getX(), scaleX);
            double y = worldToScreenY(platform.getY(), scaleY);
            double drawW = platform.getWidth() * scaleX;
            double drawH = platform.getHeight() * scaleY;
            gc.setFill(Color.web("#3b4658"));
            gc.fillRoundRect(x, y + 4, drawW, drawH, 12, 12);
            gc.setFill(Color.web("#64748b"));
            gc.fillRoundRect(x, y, drawW, drawH, 12, 12);
            gc.setFill(Color.web("#94a3b8"));
            gc.fillRoundRect(x + 8, y + 4, Math.max(0, drawW - 16), Math.max(6, drawH * 0.25), 10, 10);
            gc.setStroke(Color.web("#cbd5e1"));
            gc.setLineWidth(1.5);
            gc.strokeRoundRect(x, y, drawW, drawH, 12, 12);
        }

        ButtonSwitch button = MainApp.sessionService.getButtonSwitchSnapshot();
        if (button != null) {
            double x = worldToScreenX(button.getX(), scaleX);
            double y = worldToScreenY(button.getY(), scaleY);
            double drawW = button.getWidth() * scaleX;
            double drawH = button.getHeight() * scaleY;
            gc.setFill(button.isPressed() ? Color.web("#ffd166") : Color.web("#7c3aed"));
            gc.fillRoundRect(x, y, drawW, drawH, 10, 10);
            gc.setFill(button.isPressed() ? Color.web("#fff1ad") : Color.web("#c4b5fd"));
            gc.fillRoundRect(x + 8, y + 2, Math.max(0, drawW - 16), Math.max(4, drawH - 6), 8, 8);
        }

        Door door = MainApp.sessionService.getDoorSnapshot();
        if (door != null && !door.isOpen()) {
            double x = worldToScreenX(door.getX(), scaleX);
            double y = worldToScreenY(door.getY(), scaleY);
            double drawW = door.getWidth() * scaleX;
            double drawH = door.getHeight() * scaleY;
            gc.setFill(Color.web("#8b5cf6"));
            gc.fillRoundRect(x, y, drawW, drawH, 14, 14);
            gc.setFill(Color.web("#6d28d9"));
            gc.fillRoundRect(x + 8, y + 10, Math.max(0, drawW - 16), Math.max(0, drawH - 20), 10, 10);
            gc.setFill(Color.web("#e9d5ff"));
            gc.fillOval(x + drawW - 18, y + drawH / 2.0 - 6, 8, 8);
        }

        for (PushBlock block : MainApp.sessionService.getPushBlocksSnapshot()) {
            double x = worldToScreenX(block.getX(), scaleX);
            double y = worldToScreenY(block.getY(), scaleY);
            double drawW = block.getWidth() * scaleX;
            double drawH = block.getHeight() * scaleY;
            gc.setFill(Color.web("#8b6b4a"));
            gc.fillRoundRect(x + 3, y + 4, drawW, drawH, 10, 10);
            gc.setFill(Color.web("#b08968"));
            gc.fillRoundRect(x, y, drawW, drawH, 10, 10);
            gc.setStroke(Color.web("#e6ccb2"));
            gc.setLineWidth(2);
            gc.strokeRoundRect(x, y, drawW, drawH, 10, 10);
            gc.setStroke(Color.web("#7f5539"));
            gc.setLineWidth(2);
            gc.strokeLine(x + drawW * 0.2, y + drawH * 0.2, x + drawW * 0.8, y + drawH * 0.8);
            gc.strokeLine(x + drawW * 0.8, y + drawH * 0.2, x + drawW * 0.2, y + drawH * 0.8);
        }

        long nowMs = System.currentTimeMillis();
        for (CollectibleItem coin : MainApp.sessionService.getCoinsSnapshot()) {
            if (!coin.isActive()) continue;
            double cr = GameConfig.COIN_SIZE / 2 * scaleX;
            double pulse = 1.0 + 0.12 * Math.sin(nowMs / 400.0 + coin.getX() * 0.01);
            double pr = cr * pulse;
            double cx = worldToScreenX(coin.getX() + GameConfig.COIN_SIZE / 2, scaleX);
            double cy = worldToScreenY(coin.getY() + GameConfig.COIN_SIZE / 2, scaleY);
            boolean large = coin.getPoints() >= GameConfig.SCORE_COIN_LARGE;
            Color coinCol = large ? Color.web("#ffa500") : Color.web("#ffd166");
            gc.setFill(coinCol.deriveColor(0, 1, 1, 0.22));
            gc.fillOval(cx - pr * 1.6, cy - pr * 1.6, pr * 3.2, pr * 3.2);
            gc.setFill(coinCol);
            gc.fillOval(cx - pr, cy - pr, pr * 2, pr * 2);
            gc.setFill(Color.web("#ffffff88"));
            gc.fillOval(cx - pr * 0.55, cy - pr * 0.72, pr * 0.55, pr * 0.52);
            gc.setStroke(large ? Color.web("#ff8c00") : Color.web("#f59e0b"));
            gc.setLineWidth(1.5);
            gc.strokeOval(cx - pr, cy - pr, pr * 2, pr * 2);
            if (large) {
                gc.setFill(Color.web("#7c2d00"));
                gc.setFont(Font.font("Arial", FontWeight.BOLD, Math.max(7, 8 * currentZoom)));
                gc.fillText("★", cx - pr * 0.38, cy + pr * 0.48);
            }
        }

        List<Player> players = MainApp.sessionService.getPlayersSnapshot().stream()
            .filter(Player::isConnected)
            .sorted(Comparator.comparingDouble(Player::getX))
            .toList();

        updateRenderStates(players, 1.0 / Math.max(1, GameConfig.FPS));

        gc.setLineWidth(8);
        for (int i = 0; i < players.size() - 1; i++) {
            RenderState a = renderStates.get(players.get(i).getId());
            RenderState b = renderStates.get(players.get(i + 1).getId());
            if (a == null || b == null) continue;
            double ddx = b.x - a.x;
            double ddy = b.y - a.y;
            double dist = Math.sqrt(ddx * ddx + ddy * ddy);
            double tension = threadTension(dist);
            Color glowColor = Color.web("#4cc9f0").interpolate(Color.web("#ef476f"), tension);
            gc.setStroke(glowColor.deriveColor(0, 1, 1, 0.27));
            double midX = (a.x + b.x) / 2.0;
            double sag = Math.min(22, Math.abs(b.x - a.x) * 0.06) * (1.05 - tension * 0.55);
            gc.beginPath();
            gc.moveTo(worldToScreenX(a.centerX(players.get(i).getWidth()), scaleX), worldToScreenY(a.centerY(players.get(i).getHeight()), scaleY));
            gc.quadraticCurveTo(worldToScreenX(midX, scaleX), worldToScreenY((a.y + b.y) / 2.0 + sag, scaleY),
                worldToScreenX(b.centerX(players.get(i + 1).getWidth()), scaleX), worldToScreenY(b.centerY(players.get(i + 1).getHeight()), scaleY));
            gc.stroke();
        }
        gc.setLineWidth(3);
        for (int i = 0; i < players.size() - 1; i++) {
            RenderState a = renderStates.get(players.get(i).getId());
            RenderState b = renderStates.get(players.get(i + 1).getId());
            if (a == null || b == null) continue;
            double ddx = b.x - a.x;
            double ddy = b.y - a.y;
            double dist = Math.sqrt(ddx * ddx + ddy * ddy);
            double tension = threadTension(dist);
            Color threadColor = Color.web("#80ed99").interpolate(Color.web("#ef476f"), tension);
            gc.setStroke(threadColor.deriveColor(0, 1, 1, 0.88));
            double midX = (a.x + b.x) / 2.0;
            double sag = Math.min(22, Math.abs(b.x - a.x) * 0.06) * (1.05 - tension * 0.55);
            gc.beginPath();
            gc.moveTo(worldToScreenX(a.centerX(players.get(i).getWidth()), scaleX), worldToScreenY(a.centerY(players.get(i).getHeight()), scaleY));
            gc.quadraticCurveTo(worldToScreenX(midX, scaleX), worldToScreenY((a.y + b.y) / 2.0 + sag, scaleY),
                worldToScreenX(b.centerX(players.get(i + 1).getWidth()), scaleX), worldToScreenY(b.centerY(players.get(i + 1).getHeight()), scaleY));
            gc.stroke();
        }

        List<PlatformTile> platformsForShadow = MainApp.sessionService.getPlatformsSnapshot();
        gc.setStroke(Color.web("#ffffff33"));
        gc.setLineWidth(1);
        gc.setLineDashes(4, 6);
        for (Player player : players) {
            RenderState state = renderStates.get(player.getId());
            if (state == null) continue;
            double shadowCenterX = worldToScreenX(state.centerX(player.getWidth()), scaleX);
            double shadowTopY = worldToScreenY(state.y + player.getHeight(), scaleY);
            double floorWorldY = 800;
            for (PlatformTile plat : platformsForShadow) {
                if (plat.getY() > state.y && plat.getY() < floorWorldY) floorWorldY = plat.getY();
            }
            double shadowBottomY = worldToScreenY(floorWorldY, scaleY);
            gc.strokeLine(shadowCenterX, shadowTopY, shadowCenterX, shadowBottomY);
        }
        gc.setLineDashes(null);

        Player localPlayer = getLocalPlayerSnapshot();
        for (Player player : players) {
            RenderState state = renderStates.get(player.getId());
            if (state == null) continue;
            Color base = GameConfig.COLORS.getOrDefault(player.getColor(), Color.WHITE);
            double x = worldToScreenX(state.x, scaleX);
            double y = worldToScreenY(state.y, scaleY);
            double drawW = player.getWidth() * scaleX;
            double drawH = player.getHeight() * scaleY;
            double stretch = state.stretch;
            double renderH = drawH * (1.0 + stretch);
            double renderW = drawW * (1.0 - stretch * 0.55);
            double adjustedX = x + (drawW - renderW) / 2.0;
            double adjustedY = y + (drawH - renderH);

            gc.setFill(Color.color(0, 0, 0, 0.22));
            gc.fillRoundRect(adjustedX + 4, adjustedY + 6, renderW, renderH, 18, 18);
            gc.setFill(player.isAtExit() ? base.brighter() : base);
            gc.fillRoundRect(adjustedX, adjustedY, renderW, renderH, 18, 18);
            gc.setFill(base.interpolate(Color.WHITE, 0.35));
            gc.fillRoundRect(adjustedX + 6, adjustedY + 5, Math.max(8, renderW - 12), Math.max(10, renderH * 0.28), 14, 14);
            gc.setStroke(localPlayer != null && player.getId().equals(localPlayer.getId()) ? Color.WHITE : base.darker());
            gc.setLineWidth(localPlayer != null && player.getId().equals(localPlayer.getId()) ? 4 : 2);
            gc.strokeRoundRect(adjustedX, adjustedY, renderW, renderH, 18, 18);

            double eyeY = adjustedY + renderH * 0.35;
            double leftEyeX = adjustedX + renderW * 0.33;
            double rightEyeX = adjustedX + renderW * 0.63;
            gc.setFill(Color.WHITE);
            gc.fillOval(leftEyeX, eyeY, 6, 6);
            gc.fillOval(rightEyeX, eyeY, 6, 6);
            gc.setFill(Color.web("#1e293b"));
            gc.fillOval(leftEyeX + 2, eyeY + 1, 2.5, 2.5);
            gc.fillOval(rightEyeX + 2, eyeY + 1, 2.5, 2.5);
            gc.setStroke(Color.web("#1e293b"));
            gc.setLineWidth(1.5);
            gc.strokeArc(adjustedX + renderW * 0.34, adjustedY + renderH * 0.52, renderW * 0.30, renderH * 0.18, 180, 180, javafx.scene.shape.ArcType.OPEN);

            gc.setFill(Color.WHITE);
            gc.setFont(Font.font("Arial", FontWeight.BOLD, Math.max(11, 12 * currentZoom)));
            gc.fillText(player.getName() + " · " + player.getScore(), adjustedX - 2, adjustedY - 12);

            if (localPlayer != null && player.getId().equals(localPlayer.getId())) {
                double targetX = worldToScreenX(player.getTargetX(), scaleX);
                double lineY = adjustedY + renderH + 6;
                gc.setStroke(Color.web("#ffffff99"));
                gc.setLineWidth(2);
                gc.strokeLine(worldToScreenX(state.centerX(player.getWidth()), scaleX), lineY, targetX, lineY);
                gc.setFill(Color.web("#ffffffcc"));
                gc.fillOval(targetX - 4, adjustedY + renderH + 2, 8, 8);
                double dxTarget = player.getTargetX() - state.centerX(player.getWidth());
                if (Math.abs(dxTarget) > GameConfig.TARGET_REACHED_TOLERANCE) {
                    double dir = Math.signum(dxTarget);
                    double tipX = targetX + dir * 14;
                    double baseX = targetX + dir * 4;
                    gc.fillPolygon(new double[]{tipX, baseX, baseX}, new double[]{lineY, lineY - 8, lineY + 8}, 3);
                }
            }
        }

        renderParticles(gc, scaleX, scaleY);
    }

    private void drawBackdrop(GraphicsContext gc, double width, double height) {
        int lvl = MainApp.sessionService.getCurrentLevelIndex();
        if (lvl == 0) {
            gc.setFill(Color.web("#0b1020")); gc.fillRect(0, 0, width, height);
            gc.setFill(Color.web("#11213a")); gc.fillOval(-120, -80, 420, 260);
            gc.setFill(Color.web("#152844")); gc.fillOval(width-320, -40, 380, 220);
            gc.setFill(Color.web("#0f1b2d")); gc.fillRect(0, height*0.72, width, height*0.28);
        } else if (lvl == 1) {
            gc.setFill(Color.web("#1a0800")); gc.fillRect(0, 0, width, height);
            gc.setFill(Color.web("#3d1500")); gc.fillOval(-80, height*0.3, 340, 300);
            gc.setFill(Color.web("#2a0e00")); gc.fillOval(width-280, -60, 360, 260);
            gc.setFill(Color.web("#2d1000")); gc.fillRect(0, height*0.72, width, height*0.28);
        } else {
            gc.setFill(Color.web("#050508")); gc.fillRect(0, 0, width, height);
            gc.setFill(Color.web("#1a003055")); gc.fillOval(-60, -60, 460, 320);
            gc.setFill(Color.web("#00102a55")); gc.fillOval(width-400, height*0.2, 440, 340);
            gc.setFill(Color.web("#0a1a0055")); gc.fillOval(width*0.3, height*0.4, 380, 280);
            gc.setFill(Color.web("#050508")); gc.fillRect(0, height*0.72, width, height*0.28);
        }
    }

    private void drawBackdropShapes(GraphicsContext gc, double width, double height, double scaleX, double scaleY) {
        gc.setFill(Color.web("#14233b"));
        for (int i = 0; i < 6; i++) {
            double x = ((i * 320.0) - (cameraX * 0.18)) % (width + 220);
            double hillX = x - 120;
            gc.fillRoundRect(hillX, height - 130 - (i % 2) * 24, 220, 140, 80, 80);
        }

        gc.setStroke(Color.web("#ffffff10"));
        gc.setLineWidth(1);
        for (int i = 0; i < 18; i++) {
            double px = ((i * 140.0) - (cameraX * 0.12)) % (width + 80);
            gc.strokeLine(px, 0, px + 24, 18);
        }
    }

    private void drawBackgroundGrid(GraphicsContext gc, double width, double height, double scaleX, double scaleY) {
        gc.setStroke(Color.web("#1a2740"));
        gc.setLineWidth(1);
        for (int gx = 0; gx <= GameConfig.LEVEL_WIDTH; gx += 80) {
            double sx = worldToScreenX(gx, scaleX);
            if (sx >= 0 && sx <= width) gc.strokeLine(sx, 0, sx, height);
        }
        for (int gy = 0; gy <= GameConfig.LEVEL_HEIGHT; gy += 80) {
            double sy = worldToScreenY(gy, scaleY);
            if (sy >= 0 && sy <= height) gc.strokeLine(0, sy, width, sy);
        }
    }

    private void refreshUI() {
        playersList.getItems().clear();
        long globalSnapshotAgeMs = Math.round(timeSinceLastSnapshot * 1000.0);
        String localId = MainApp.sessionService.getLocalPlayerId();
        for (Player player : MainApp.scoreBoardObserver.getEntries()) {
            String status = !player.isConnected() ? "desconectado"
                : player.isAtExit() ? "salida"
                : player.isAlive() ? "activo" : "caido";
            String netStatus;
            int warnings = peerWarningCounts.getOrDefault(player.getId(), 0);
            if (!player.isConnected()) {
                netStatus = "sin señal";
            } else if (MainApp.sessionService.isHost()) {
                if (player.getId().equals(localId)) {
                    netStatus = "host local";
                } else {
                    Long ageMs = MainApp.sessionService.getPeerAgeMillis(player.getId());
                    if (ageMs != null && ageMs > (long) (GameConfig.SNAPSHOT_STALE_WARNING_SECONDS * 1000)) {
                        if (stalePeers.add(player.getId())) {
                            peerWarningCounts.merge(player.getId(), 1, Integer::sum);
                            warnings = peerWarningCounts.getOrDefault(player.getId(), 0);
                        }
                    } else {
                        stalePeers.remove(player.getId());
                    }
                    netStatus = ageMs == null ? "udp ?" : "udp " + ageMs + " ms";
                }
            } else {
                netStatus = player.getId().equals(localId) ? "cliente local" : "sync " + globalSnapshotAgeMs + " ms";
            }
            playersList.getItems().add(
                player.getName() + " · " + player.getScore() + " pts · " + status
                    + " · " + netStatus + " · warn " + warnings + " · caidas " + player.getDeaths());
        }

        eventLog.getItems().setAll(MainApp.eventLogObserver.getEntries());
        Door door = MainApp.sessionService.getDoorSnapshot();
        double maxThreadDistance = maxVisibleThreadDistance(MainApp.sessionService.getPlayersSnapshot().stream()
            .filter(Player::isConnected)
            .toList());
        levelLabel.setText("Nivel " + (MainApp.sessionService.getCurrentLevelIndex() + 1) + "/" + Math.max(1, MainApp.sessionService.getTotalLevels()));
        roomStatusLabel.setText(door != null && door.isOpen()
            ? "Puerta abierta: avancen juntos hacia la salida"
            : switch (MainApp.sessionService.getCurrentLevelIndex()) {
                case 0 -> "Nivel 1: activen el boton y crucen juntos";
                case 1 -> "Nivel 2: mantengan el hilo corto en los saltos";
                case 2 -> "Nivel 3: coordinen el ritmo y cuiden las caidas";
                case 3 -> "Nivel 4: agrupense, recojan monedas y avancen juntos";
                default -> "Nivel 5: cierre final, puntaje alto y grupo unido";
            });
        threadLabel.setText(threadText(maxThreadDistance));
        networkLabel.setText(connectionText());
    }

    private void updateTimer() {
        timerLabel.setText(String.format("Tiempo: %.1fs", MainApp.sessionService.getElapsedTime()));
        networkLabel.setText(connectionText());
    }

    private void onGameOver() {
        if (gameLoop != null) gameLoop.stop();
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/dino/views/game_over.fxml"));
            Scene scene = new Scene(loader.load(), GameConfig.WINDOW_WIDTH, GameConfig.WINDOW_HEIGHT);
            MainApp.getStage().setScene(scene);
        } catch (Exception e) {
            System.err.println("[GameController] Game over: " + e.getMessage());
        }
    }

    private void updateCamera(double dt) {
        Player localPlayer = getLocalPlayerSnapshot();
        if (localPlayer == null) return;

        double targetZoom = calculateZoomForSpread(MainApp.sessionService.getPlayersSnapshot());
        currentZoom += (targetZoom - currentZoom) * Math.min(1.0, GameConfig.CAMERA_SMOOTHING * (dt * 60.0));

        List<Player> connectedPlayers = MainApp.sessionService.getPlayersSnapshot().stream()
            .filter(Player::isConnected)
            .toList();
        double groupCenterX = connectedPlayers.stream().mapToDouble(Player::getCenterX).average().orElse(localPlayer.getCenterX());
        double groupCenterY = connectedPlayers.stream().mapToDouble(Player::getCenterY).average().orElse(localPlayer.getCenterY());
        double focusX = localPlayer.getCenterX() * (1.0 - GameConfig.CAMERA_GROUP_INFLUENCE)
            + groupCenterX * GameConfig.CAMERA_GROUP_INFLUENCE;
        double focusY = localPlayer.getCenterY() * (1.0 - GameConfig.CAMERA_GROUP_INFLUENCE)
            + groupCenterY * GameConfig.CAMERA_GROUP_INFLUENCE;

        double targetX = clampCameraX(focusX - getViewportWorldWidth() / 2.0);
        double targetY = clampCameraY(focusY - getViewportWorldHeight() / 2.0);
        cameraX += (targetX - cameraX) * Math.min(1.0, GameConfig.CAMERA_SMOOTHING * (dt * 60.0));
        cameraY += (targetY - cameraY) * Math.min(1.0, GameConfig.CAMERA_SMOOTHING * (dt * 60.0));
        cameraX = clampCameraX(cameraX);
        cameraY = clampCameraY(cameraY);
    }

    private void updateFeedback(double dt) {
        if (feedbackTimer <= 0) return;
        feedbackTimer = Math.max(0, feedbackTimer - dt);
        feedbackLabel.setVisible(feedbackTimer > 0);
        feedbackLabel.setOpacity(Math.min(1.0, feedbackTimer / 1.4));
        if (feedbackTimer == 0) feedbackLabel.setText("");
    }

    private void showFeedback(String text, String colorHex) {
        feedbackTimer = 1.4;
        feedbackLabel.setText(text);
        feedbackLabel.setStyle(
            "-fx-text-fill: " + colorHex + "; -fx-font-size: 18px; -fx-font-weight: bold;"
                + " -fx-background-color: rgba(12,18,33,0.78); -fx-padding: 8 14;"
                + " -fx-background-radius: 16;"
        );
        feedbackLabel.setVisible(true);
        feedbackLabel.setOpacity(1);
    }

    private void onReachedExitEvent(String playerId) {
        String localId = MainApp.sessionService.getLocalPlayerId();
        if (localId != null && localId.equals(playerId)) {
            Platform.runLater(() -> {
                showFeedback("Llegaste a la salida", "#80ed99");
                spawnBurst(arenaCanvas.getWidth() * 0.5, 180, Color.web("#80ed99"), 18);
            });
        }
    }

    private void onJumpEvent(String playerId) {
        String localId = MainApp.sessionService.getLocalPlayerId();
        if (localId == null || !localId.equals(playerId)) return;
        Platform.runLater(() -> spawnBurst(arenaCanvas.getWidth() * 0.48, arenaCanvas.getHeight() * 0.64, Color.web("#c4b5fd"), 8));
    }

    private void onCollisionEvent(Map<String, Object> payload) {
        String localId = MainApp.sessionService.getLocalPlayerId();
        if (localId == null) return;
        if (!localId.equals(payload.get("playerA")) && !localId.equals(payload.get("playerB"))) return;
        Platform.runLater(() -> spawnBurst(arenaCanvas.getWidth() * 0.5, arenaCanvas.getHeight() * 0.6, Color.web("#fca5a5"), 8));
    }

    private void onThreadEvent(Map<String, Object> payload) {
        String localId = MainApp.sessionService.getLocalPlayerId();
        if (localId == null) return;
        if (!localId.equals(payload.get("playerA")) && !localId.equals(payload.get("playerB"))) return;
        Platform.runLater(() -> spawnBurst(arenaCanvas.getWidth() * 0.5, arenaCanvas.getHeight() * 0.45, Color.web("#80ed99"), 6));
    }

    private void onScoreChangedEvent(Map<String, Object> payload) {
        String localId = MainApp.sessionService.getLocalPlayerId();
        if (localId == null || !localId.equals(payload.get("playerId"))) return;
        int delta = ((Number) payload.getOrDefault("delta", 0)).intValue();
        String sign = delta >= 0 ? "+" : "";
        Platform.runLater(() -> {
            showFeedback(sign + delta + " puntos", delta >= 0 ? "#8be9fd" : "#ffadad");
            spawnBurst(arenaCanvas.getWidth() * 0.52, 120, delta >= 0 ? Color.web("#8be9fd") : Color.web("#ffadad"), 10);
        });
    }

    private Player getLocalPlayerSnapshot() {
        String localId = MainApp.sessionService.getLocalPlayerId();
        if (localId == null) return null;
        for (Player player : MainApp.sessionService.getPlayersSnapshot()) {
            if (localId.equals(player.getId())) return player;
        }
        return null;
    }

    private double calculateZoomForSpread(List<Player> players) {
        List<Player> active = new ArrayList<>();
        for (Player player : players) {
            if (player.isConnected()) active.add(player);
        }
        if (active.size() < 2) return GameConfig.BASE_ZOOM;

        double minX = active.stream().mapToDouble(Player::getCenterX).min().orElse(0);
        double maxX = active.stream().mapToDouble(Player::getCenterX).max().orElse(0);
        double spread = maxX - minX;
        double zoom = GameConfig.BASE_ZOOM - (spread / 1600.0);
        return Math.max(GameConfig.MIN_ZOOM, Math.min(GameConfig.MAX_ZOOM, zoom));
    }

    private double getViewportWorldWidth() {
        return GameConfig.VIEWPORT_W / currentZoom;
    }

    private double getViewportWorldHeight() {
        return GameConfig.VIEWPORT_H / currentZoom;
    }

    private double clampCameraX(double x) {
        return Math.max(0, Math.min(GameConfig.LEVEL_WIDTH - getViewportWorldWidth(), x));
    }

    private double clampCameraY(double y) {
        return Math.max(0, Math.min(GameConfig.LEVEL_HEIGHT - getViewportWorldHeight(), y));
    }

    private double worldToScreenX(double worldX, double scaleX) {
        return (worldX - cameraX) * scaleX;
    }

    private double worldToScreenY(double worldY, double scaleY) {
        return (worldY - cameraY) * scaleY;
    }

    private void updateRenderStates(List<Player> players, double dt) {
        double nowSeconds = System.nanoTime() / 1_000_000_000.0;
        for (Player player : players) {
            RenderState state = renderStates.computeIfAbsent(player.getId(), ignored -> new RenderState(player.getX(), player.getY()));
            if (MainApp.sessionService.isHost()) {
                state.x = player.getX();
                state.y = player.getY();
                state.vx = player.getVx();
                state.vy = player.getVy();
            } else {
                boolean isLocalClientPlayer = player.getId().equals(MainApp.sessionService.getLocalPlayerId());
                if (isLocalClientPlayer) {
                    double predictionWindow = Math.min(GameConfig.LOCAL_CLIENT_PREDICTION_SECONDS, lastSnapshotAgeForRender);
                    double targetX = player.getX() + player.getVx() * predictionWindow;
                    double targetY = player.getY() + player.getVy() * predictionWindow;
                    double blend = 1.0 - Math.pow(1.0 - GameConfig.REMOTE_SMOOTHING, Math.max(1.0, dt * 60.0));
                    state.x += (targetX - state.x) * blend;
                    state.y += (targetY - state.y) * blend;
                    state.vx += (player.getVx() - state.vx) * blend;
                    state.vy += (player.getVy() - state.vy) * blend;
                } else {
                    SnapshotSample interpolated = sampleForRender(player, nowSeconds);
                    double targetX = interpolated != null ? interpolated.x : player.getX();
                    double targetY = interpolated != null ? interpolated.y : player.getY();
                    double targetVx = interpolated != null ? interpolated.vx : player.getVx();
                    double targetVy = interpolated != null ? interpolated.vy : player.getVy();
                    double blend = 1.0 - Math.pow(1.0 - GameConfig.REMOTE_SMOOTHING, Math.max(1.0, dt * 60.0));
                    state.x += (targetX - state.x) * blend;
                    state.y += (targetY - state.y) * blend;
                    state.vx += (targetVx - state.vx) * blend;
                    state.vy += (targetVy - state.vy) * blend;
                }
            }
            double targetStretch = Math.max(-0.16, Math.min(0.19, state.vy / 920.0));
            double stretchBlend = 1.0 - Math.pow(1.0 - GameConfig.VISUAL_STRETCH_SMOOTHING, Math.max(1.0, dt * 60.0));
            state.stretch += (targetStretch - state.stretch) * stretchBlend;
        }
        renderStates.keySet().removeIf(id -> players.stream().noneMatch(player -> player.getId().equals(id)));
        snapshotBuffers.keySet().removeIf(id -> players.stream().noneMatch(player -> player.getId().equals(id)));
    }

    private void captureSnapshotSamples() {
        if (MainApp.sessionService.isHost()) return;
        double nowSeconds = System.nanoTime() / 1_000_000_000.0;
        for (Player player : MainApp.sessionService.getPlayersSnapshot()) {
            Deque<SnapshotSample> buffer = snapshotBuffers.computeIfAbsent(player.getId(), ignored -> new ArrayDeque<>());
            buffer.addLast(new SnapshotSample(player.getX(), player.getY(), player.getVx(), player.getVy(), nowSeconds));
            while (buffer.size() > GameConfig.MAX_BUFFERED_SNAPSHOTS) {
                buffer.removeFirst();
            }
        }
    }

    private SnapshotSample sampleForRender(Player player, double nowSeconds) {
        Deque<SnapshotSample> buffer = snapshotBuffers.get(player.getId());
        if (buffer == null || buffer.isEmpty()) return null;

        double renderTime = nowSeconds - GameConfig.SNAPSHOT_INTERPOLATION_DELAY_SECONDS;
        SnapshotSample previous = null;
        SnapshotSample next = null;
        for (SnapshotSample sample : buffer) {
            if (sample.timestamp <= renderTime) {
                previous = sample;
                continue;
            }
            next = sample;
            break;
        }

        if (previous != null && next != null && next.timestamp > previous.timestamp) {
            double alpha = (renderTime - previous.timestamp) / (next.timestamp - previous.timestamp);
            alpha = Math.max(0.0, Math.min(1.0, alpha));
            return SnapshotSample.interpolate(previous, next, alpha);
        }

        SnapshotSample reference = previous != null ? previous : buffer.peekFirst();
        if (reference == null) return null;
        double prediction = Math.min(GameConfig.REMOTE_PREDICTION_SECONDS, Math.max(0.0, renderTime - reference.timestamp));
        return new SnapshotSample(
            reference.x + reference.vx * prediction,
            reference.y + reference.vy * prediction,
            reference.vx,
            reference.vy,
            renderTime
        );
    }

    private void updateParticles(double dt) {
        particles.removeIf(particle -> {
            particle.life -= dt;
            particle.x += particle.vx * dt;
            particle.y += particle.vy * dt;
            particle.vy += 160 * dt;
            return particle.life <= 0;
        });
    }

    private void renderParticles(GraphicsContext gc, double scaleX, double scaleY) {
        for (Particle particle : particles) {
            double alpha = Math.max(0, particle.life / particle.maxLife);
            gc.setFill(particle.color.deriveColor(0, 1, 1, alpha));
            gc.fillOval(particle.x - particle.size / 2.0, particle.y - particle.size / 2.0, particle.size, particle.size);
        }
    }

    private void spawnBurst(double screenX, double screenY, Color color, int count) {
        for (int i = 0; i < count; i++) {
            double angle = (Math.PI * 2 * i) / Math.max(1, count);
            double speed = 36 + (i % 4) * 12;
            particles.add(new Particle(
                screenX,
                screenY,
                Math.cos(angle) * speed,
                Math.sin(angle) * speed - 18,
                0.55 + (i % 3) * 0.08,
                4 + (i % 3),
                color
            ));
        }
    }

    private String connectionText() {
        if (MainApp.sessionService.isHost()) return "UDP host estable · 30 Hz";
        long ms = Math.round(timeSinceLastSnapshot * 1000.0);
        if (timeSinceLastSnapshot > 3.0) return "UDP: sin señal — reconectando...";
        if (timeSinceLastSnapshot < GameConfig.SNAPSHOT_STALE_WARNING_SECONDS) return "UDP estable · " + ms + " ms";
        if (timeSinceLastSnapshot < GameConfig.SNAPSHOT_STALE_WARNING_SECONDS * 2.0) return "UDP con retraso · " + ms + " ms";
        return "UDP inestable · " + ms + " ms";
    }

    private double maxVisibleThreadDistance(List<Player> players) {
        double max = 0;
        for (int i = 0; i < players.size() - 1; i++) {
            for (int j = i + 1; j < players.size(); j++) {
                double dx = players.get(j).getCenterX() - players.get(i).getCenterX();
                double dy = players.get(j).getCenterY() - players.get(i).getCenterY();
                max = Math.max(max, Math.sqrt(dx * dx + dy * dy));
            }
        }
        return max;
    }

    private double threadTension(double distance) {
        return Math.max(0.0, Math.min(1.0,
            (distance - GameConfig.THREAD_REST_DISTANCE) / Math.max(1.0, GameConfig.THREAD_HARD_LIMIT - GameConfig.THREAD_REST_DISTANCE)));
    }

    private String threadText(double maxDistance) {
        String state;
        if (maxDistance < GameConfig.THREAD_TENSE_DISTANCE) {
            state = "relajado";
        } else if (maxDistance < GameConfig.THREAD_CRITICAL_DISTANCE) {
            state = "tenso";
        } else {
            state = "critico";
        }
        return "Hilo " + state + " · " + (int) maxDistance + "/" + (int) GameConfig.THREAD_HARD_LIMIT + " px";
    }

    private void armCriticalSnapshot(String type, int version) {
        pendingCriticalType = type;
        pendingCriticalVersion = version;
        pendingCriticalAge = 0;
        criticalAckedPeers.clear();
    }

    private void updateCriticalAckWindow(double dt) {
        if (pendingCriticalType == null) return;
        pendingCriticalAge += dt;
        List<String> waiting = MainApp.sessionService.getPlayersSnapshot().stream()
            .filter(Player::isConnected)
            .map(Player::getId)
            .filter(id -> !id.equals(MainApp.sessionService.getLocalPlayerId()))
            .filter(id -> !criticalAckedPeers.contains(id))
            .toList();
        if (waiting.isEmpty() || pendingCriticalAge >= GameConfig.CRITICAL_ACK_TIMEOUT_SECONDS) {
            pendingCriticalType = null;
            pendingCriticalVersion = 0;
            pendingCriticalAge = 0;
            criticalAckedPeers.clear();
        }
    }

    private void attachCriticalMetadata(Map<String, Object> snapshot) {
        if (pendingCriticalType == null) return;
        snapshot.put("criticalType", pendingCriticalType);
        snapshot.put("criticalVersion", pendingCriticalVersion);
    }

    private void acknowledgeCriticalSnapshotIfNeeded(Map<String, Object> snapshot) {
        String criticalType = (String) snapshot.get("criticalType");
        Number criticalVersion = (Number) snapshot.get("criticalVersion");
        if (criticalType == null || criticalVersion == null || MainApp.sessionService.isHost()) return;
        try {
            Map<String, Object> ack = serializer.build(
                MessageSerializer.ACK,
                "playerId", MainApp.sessionService.getLocalPlayerId(),
                "criticalType", criticalType,
                "criticalVersion", criticalVersion.intValue()
            );
            MainApp.udpPeer.send(ack,
                InetAddress.getByName(MainApp.sessionService.getHostIp()),
                MainApp.sessionService.getHostPort());
        } catch (Exception e) {
            System.err.println("[GameController] Ack error: " + e.getMessage());
        }
    }

    private static final class RenderState {
        double x;
        double y;
        double vx;
        double vy;
        double stretch;

        RenderState(double x, double y) {
            this.x = x;
            this.y = y;
        }

        double centerX(double width) { return x + width / 2.0; }
        double centerY(double height) { return y + height / 2.0; }
    }

    private static final class SnapshotSample {
        final double x;
        final double y;
        final double vx;
        final double vy;
        final double timestamp;

        SnapshotSample(double x, double y, double vx, double vy, double timestamp) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.timestamp = timestamp;
        }

        static SnapshotSample interpolate(SnapshotSample a, SnapshotSample b, double alpha) {
            return new SnapshotSample(
                a.x + (b.x - a.x) * alpha,
                a.y + (b.y - a.y) * alpha,
                a.vx + (b.vx - a.vx) * alpha,
                a.vy + (b.vy - a.vy) * alpha,
                a.timestamp + (b.timestamp - a.timestamp) * alpha
            );
        }
    }

    private static final class Particle {
        double x;
        double y;
        double vx;
        double vy;
        double life;
        double maxLife;
        double size;
        Color color;

        Particle(double x, double y, double vx, double vy, double life, double size, Color color) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.life = life;
            this.maxLife = life;
            this.size = size;
            this.color = color;
        }
    }
}
