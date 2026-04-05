package com.dino.presentation.controllers;

import com.dino.MainApp;
import com.dino.domain.entities.CollectibleItem;
import com.dino.domain.entities.PenaltyZone;
import com.dino.domain.entities.Player;
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
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.util.Pair;

import java.net.InetAddress;
import java.net.URL;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.ResourceBundle;

public class GameController implements Initializable {
    @FXML private Canvas arenaCanvas;
    @FXML private ListView<String> scoreBoard;
    @FXML private ListView<String> eventLog;
    @FXML private Label timerLabel;
    @FXML private Label massLabel;
    @FXML private Label zoomLabel;
    @FXML private Label feedbackLabel;

    private final MessageSerializer serializer = new MessageSerializer();
    private AnimationTimer gameLoop;
    private long lastNano = 0;
    private double snapshotTimer = 0;
    private static final double SNAPSHOT_INTERVAL = 1.0 / 10;
    private static final double CAMERA_SMOOTHING = 0.16;
    private static final double FEEDBACK_DURATION_SECONDS = 1.6;
    private static final double BASE_ZOOM = 1.0;
    private static final double MIN_ZOOM = 0.62;
    private static final double MAX_ZOOM = 1.2;

    private static final String[] PLAYER_COLORS = {"#e94560", "#4fc3f7", "#81c784", "#ffb74d"};
    private double cameraX = com.dino.config.GameConfig.ARENA_X;
    private double cameraY = com.dino.config.GameConfig.ARENA_Y;
    private double currentZoom = BASE_ZOOM;
    private String feedbackText = "";
    private double feedbackTimer = 0;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        MainApp.eventBus.subscribe(EventNames.SNAPSHOT_RECEIVED, e -> Platform.runLater(this::refreshUI));
        MainApp.eventBus.subscribe(EventNames.GAME_OVER, e -> {
            if (MainApp.sessionService.isHost()) broadcastGameOver();
            Platform.runLater(this::onGameOver);
        });
        MainApp.eventBus.subscribe(EventNames.ITEM_COLLECTED, e -> onGameplayEvent(e, "Pellet +", "#ffd166"));
        MainApp.eventBus.subscribe(EventNames.PLAYER_CONSUMED, e -> onConsumptionEvent(e));
        MainApp.eventBus.subscribe(EventNames.VIRUS_TRIGGERED, e -> onVirusEvent(e));

        feedbackLabel.setVisible(false);

        arenaCanvas.setOnMouseClicked(ev -> {
            Pair<Double, Double> target = canvasToWorld(ev.getX(), ev.getY());
            sendInput(target.getKey(), target.getValue());
        });

        startGameLoop();
    }

    private void startGameLoop() {
        gameLoop = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (lastNano == 0) { lastNano = now; return; }
                double dt = (now - lastNano) / 1_000_000_000.0;
                lastNano = now;

                // Network receive
                if (MainApp.udpPeer != null && MainApp.udpPeer.isBound()) pollIncomingMessages();

                // Host tick + broadcast
                if (MainApp.sessionService.isHost() && MainApp.hostMatchService != null) {
                    MainApp.hostMatchService.tick(dt);
                    snapshotTimer += dt;
                    if (snapshotTimer >= SNAPSHOT_INTERVAL) {
                        snapshotTimer = 0;
                        Map<String, Object> snapshot = MainApp.sessionService.getSnapshotData();
                        snapshot.put("type", MessageSerializer.SNAPSHOT);
                        MainApp.sessionService.updateFromSnapshot(snapshot);
                        MainApp.udpPeer.broadcast(snapshot, MainApp.sessionService.getRemotePeerAddresses());
                    }
                }

                updateCamera(dt);
                updateFeedback(dt);
                render();
                updateTimer();
            }
        };
        gameLoop.start();
    }

    private void sendInput(double x, double y) {
        if (MainApp.sessionService.isHost()) {
            if (MainApp.hostMatchService != null)
                MainApp.hostMatchService.handleInput(MainApp.sessionService.getLocalPlayerId(), x, y);
        } else {
            try {
                Map<String, Object> msg = serializer.build(MessageSerializer.INPUT,
                    "playerId", MainApp.sessionService.getLocalPlayerId(), "targetX", x, "targetY", y);
                MainApp.udpPeer.send(msg,
                    InetAddress.getByName(MainApp.sessionService.getHostIp()),
                    MainApp.sessionService.getHostPort());
            } catch (Exception e) {
                System.err.println("[GameController] Input error: " + e.getMessage());
            }
        }
    }

    private void pollIncomingMessages() {
        while (true) {
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
                Number targetY = (Number) msg.get("targetY");
                if (playerId != null && targetX != null && targetY != null) {
                    MainApp.sessionService.registerPeerAddress(playerId, sender);
                    MainApp.hostMatchService.handleInput(playerId, targetX.doubleValue(), targetY.doubleValue());
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

        if (MessageSerializer.SNAPSHOT.equals(messageType)) {
            MainApp.sessionService.updateFromSnapshot(msg);
        } else if (MessageSerializer.GAME_OVER.equals(messageType)) {
            MainApp.sessionService.updateFromSnapshot(msg);
            Platform.runLater(this::onGameOver);
        }
    }

    private void broadcastGameOver() {
        if (MainApp.udpPeer == null || !MainApp.udpPeer.isBound()) return;
        Map<String, Object> payload = MainApp.sessionService.getSnapshotData();
        payload.put("type", MessageSerializer.GAME_OVER);
        MainApp.udpPeer.broadcast(payload, MainApp.sessionService.getRemotePeerAddresses());
    }

    private Pair<Double, Double> canvasToWorld(double canvasX, double canvasY) {
        double w = arenaCanvas.getWidth();
        double h = arenaCanvas.getHeight();
        double viewportW = getViewportWorldWidth();
        double viewportH = getViewportWorldHeight();
        double scaleX = w / viewportW;
        double scaleY = h / viewportH;
        double worldX = cameraX + (canvasX / scaleX);
        double worldY = cameraY + (canvasY / scaleY);
        return new Pair<>(worldX, worldY);
    }

    private void render() {
        double w = arenaCanvas.getWidth();   // fixed 900 from FXML
        double h = arenaCanvas.getHeight();  // fixed 620 from FXML

        GraphicsContext gc = arenaCanvas.getGraphicsContext2D();
        gc.setFill(Color.web("#0b1020")); gc.fillRect(0, 0, w, h);
        double viewportW = getViewportWorldWidth();
        double viewportH = getViewportWorldHeight();
        double scaleX = w / viewportW;
        double scaleY = h / viewportH;
        double viewX = cameraX;
        double viewY = cameraY;

        gc.setStroke(Color.web("#162842"));
        gc.setLineWidth(1);
        for (double gx = com.dino.config.GameConfig.ARENA_X; gx <= com.dino.config.GameConfig.ARENA_X + com.dino.config.GameConfig.ARENA_W; gx += 100) {
            double sx = (gx - viewX) * scaleX;
            if (sx >= 0 && sx <= w) gc.strokeLine(sx, 0, sx, h);
        }
        for (double gy = com.dino.config.GameConfig.ARENA_Y; gy <= com.dino.config.GameConfig.ARENA_Y + com.dino.config.GameConfig.ARENA_H; gy += 100) {
            double sy = (gy - viewY) * scaleY;
            if (sy >= 0 && sy <= h) gc.strokeLine(0, sy, w, sy);
        }

        gc.setStroke(Color.web("#333366"));
        gc.setLineWidth(3);
        double borderX = (com.dino.config.GameConfig.ARENA_X - viewX) * scaleX;
        double borderY = (com.dino.config.GameConfig.ARENA_Y - viewY) * scaleY;
        double borderW = com.dino.config.GameConfig.ARENA_W * scaleX;
        double borderH = com.dino.config.GameConfig.ARENA_H * scaleY;
        gc.strokeRect(borderX, borderY, borderW, borderH);

        for (PenaltyZone zone : MainApp.sessionService.getPenaltyZonesSnapshot()) {
            double sx = (zone.getX() - viewX) * scaleX, sy = (zone.getY() - viewY) * scaleY;
            double sr = zone.getRadius() * Math.min(scaleX, scaleY);
            gc.setFill(Color.web("#49d86b33"));
            gc.fillOval(sx - sr, sy - sr, sr * 2, sr * 2);
            gc.setStroke(Color.web("#5bd65b"));
            gc.setLineWidth(2);
            gc.strokeOval(sx - sr, sy - sr, sr * 2, sr * 2);
            gc.setFill(Color.web("#78ff8f"));
            gc.fillOval(sx - sr * 0.22, sy - sr * 0.22, sr * 0.44, sr * 0.44);
        }

        for (CollectibleItem item : MainApp.sessionService.getItemsSnapshot()) {
            if (!item.isActive()) continue;
            double sx = (item.getX() - viewX) * scaleX, sy = (item.getY() - viewY) * scaleY;
            double r = com.dino.config.GameConfig.FOOD_RADIUS * Math.min(scaleX, scaleY);
            gc.setFill(Color.web("#ffe08a"));
            gc.fillOval(sx - r, sy - r, r * 2, r * 2);
            gc.setStroke(Color.web("#ffb84d"));
            gc.setLineWidth(1);
            gc.strokeOval(sx - r, sy - r, r * 2, r * 2);
        }

        Player localPlayer = getLocalPlayerSnapshot();
        int ci = 0;
        for (Player p : MainApp.sessionService.getPlayersSnapshot()) {
            if (!p.isConnected()) continue;
            double sx = (p.getX() - viewX) * scaleX, sy = (p.getY() - viewY) * scaleY;
            double radius = p.getRadius(com.dino.config.GameConfig.PLAYER_RADIUS_SCALE) * Math.min(scaleX, scaleY);
            Color base = Color.web(PLAYER_COLORS[ci % PLAYER_COLORS.length]);
            gc.setFill(base.deriveColor(0, 1, 1.12, 0.96));
            gc.fillOval(sx - radius, sy - radius, radius * 2, radius * 2);
            gc.setStroke(base.deriveColor(0, 1, 0.7, 1));
            gc.setLineWidth(localPlayer != null && p.getId().equals(localPlayer.getId()) ? 4 : 2);
            gc.strokeOval(sx - radius, sy - radius, radius * 2, radius * 2);

            gc.setFill(Color.WHITE);
            gc.setFont(Font.font(Math.max(11, radius * 0.34)));
            gc.fillText(p.getName(), sx - radius * 0.48, sy - radius - 8);
            gc.setFont(Font.font(Math.max(10, radius * 0.30)));
            gc.fillText(String.valueOf(p.getScore()), sx - radius * 0.20, sy + 4);

            if (localPlayer != null && p.getId().equals(localPlayer.getId())) {
                gc.setStroke(Color.web("#ffffff88"));
                gc.setLineWidth(1.5);
                gc.strokeOval(sx - radius - 8, sy - radius - 8, radius * 2 + 16, radius * 2 + 16);
            }
            ci++;
        }
    }

    private void updateTimer() {
        int secs = (int) Math.ceil(MainApp.sessionService.getGameTimer());
        timerLabel.setText(String.valueOf(secs));
    }

    private void refreshUI() {
        scoreBoard.getItems().clear();
        MainApp.scoreBoardObserver.getEntries()
            .forEach(p -> scoreBoard.getItems().add(p.getName() + ": " + p.getScore() + " masa"));
        eventLog.getItems().clear();
        eventLog.getItems().addAll(MainApp.eventLogObserver.getEntries());

        Player localPlayer = getLocalPlayerSnapshot();
        if (localPlayer != null) {
            massLabel.setText("Masa: " + localPlayer.getScore());
            zoomLabel.setText(String.format("Zoom: %.2fx", currentZoom));
        }
    }

    private void onGameOver() {
        if (gameLoop != null) gameLoop.stop();
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/dino/views/game_over.fxml"));
            Scene scene = new Scene(loader.load(), 1280, 780);
            MainApp.getStage().setScene(scene);
        } catch (Exception e) {
            System.err.println("[GameController] Game over: " + e.getMessage());
        }
    }

    private void updateCamera(double dt) {
        Player localPlayer = getLocalPlayerSnapshot();
        if (localPlayer == null) return;

        double targetZoom = calculateZoomForMass(localPlayer.getMass());
        currentZoom += (targetZoom - currentZoom) * Math.min(1.0, CAMERA_SMOOTHING * (dt * 60.0));

        double targetX = clampCameraX(localPlayer.getX() - getViewportWorldWidth() / 2.0);
        double targetY = clampCameraY(localPlayer.getY() - getViewportWorldHeight() / 2.0);
        cameraX += (targetX - cameraX) * Math.min(1.0, CAMERA_SMOOTHING * (dt * 60.0));
        cameraY += (targetY - cameraY) * Math.min(1.0, CAMERA_SMOOTHING * (dt * 60.0));
        cameraX = clampCameraX(cameraX);
        cameraY = clampCameraY(cameraY);
    }

    private void updateFeedback(double dt) {
        if (feedbackTimer <= 0) return;
        feedbackTimer = Math.max(0, feedbackTimer - dt);
        double alpha = Math.min(1.0, feedbackTimer / FEEDBACK_DURATION_SECONDS);
        feedbackLabel.setVisible(alpha > 0);
        feedbackLabel.setOpacity(alpha);
        if (alpha == 0) feedbackLabel.setText("");
    }

    private void showFeedback(String text, String colorHex) {
        feedbackText = text;
        feedbackTimer = FEEDBACK_DURATION_SECONDS;
        feedbackLabel.setText(feedbackText);
        feedbackLabel.setStyle(
            "-fx-text-fill: " + colorHex + "; -fx-font-size: 20px; -fx-font-weight: bold;"
                + " -fx-background-color: rgba(13,13,26,0.72); -fx-padding: 8 14;"
                + " -fx-background-radius: 16;"
        );
        feedbackLabel.setVisible(true);
        feedbackLabel.setOpacity(1.0);
    }

    private void onGameplayEvent(Map<String, Object> payload, String text, String color) {
        if (isLocalPlayerEvent(payload, "playerId")) {
            Platform.runLater(() -> showFeedback(text, color));
        }
    }

    private void onConsumptionEvent(Map<String, Object> payload) {
        String localId = MainApp.sessionService.getLocalPlayerId();
        if (localId == null) return;
        String predatorId = (String) payload.get("playerId");
        String targetId = (String) payload.get("targetId");
        if (localId.equals(predatorId)) {
            Platform.runLater(() -> showFeedback("Devoraste a un rival", "#8be9fd"));
        } else if (localId.equals(targetId)) {
            Platform.runLater(() -> showFeedback("Te devoraron", "#ff6b6b"));
        }
    }

    private void onVirusEvent(Map<String, Object> payload) {
        if (isLocalPlayerEvent(payload, "playerId")) {
            Platform.runLater(() -> showFeedback("Virus activado", "#7dff7d"));
        }
    }

    private boolean isLocalPlayerEvent(Map<String, Object> payload, String key) {
        String localId = MainApp.sessionService.getLocalPlayerId();
        return localId != null && localId.equals(payload.get(key));
    }

    private Player getLocalPlayerSnapshot() {
        String localId = MainApp.sessionService.getLocalPlayerId();
        if (localId == null) return null;
        for (Player player : MainApp.sessionService.getPlayersSnapshot()) {
            if (localId.equals(player.getId())) return player;
        }
        return null;
    }

    private double calculateZoomForMass(double mass) {
        double normalized = Math.max(1.0, mass / com.dino.config.GameConfig.PLAYER_START_MASS);
        double zoom = BASE_ZOOM / Math.pow(normalized, 0.16);
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }

    private double getViewportWorldWidth() {
        return com.dino.config.GameConfig.VIEWPORT_W / currentZoom;
    }

    private double getViewportWorldHeight() {
        return com.dino.config.GameConfig.VIEWPORT_H / currentZoom;
    }

    private double clampCameraX(double x) {
        double minX = com.dino.config.GameConfig.ARENA_X;
        double maxX = com.dino.config.GameConfig.ARENA_X + com.dino.config.GameConfig.ARENA_W - getViewportWorldWidth();
        return Math.max(minX, Math.min(maxX, x));
    }

    private double clampCameraY(double y) {
        double minY = com.dino.config.GameConfig.ARENA_Y;
        double maxY = com.dino.config.GameConfig.ARENA_Y + com.dino.config.GameConfig.ARENA_H - getViewportWorldHeight();
        return Math.max(minY, Math.min(maxY, y));
    }
}
