package com.dino.presentation.controllers;

import com.dino.MainApp;
import com.dino.config.GameConfig;
import com.dino.domain.entities.ButtonSwitch;
import com.dino.domain.entities.Door;
import com.dino.domain.entities.ExitZone;
import com.dino.domain.entities.PlatformTile;
import com.dino.domain.entities.Player;
import com.dino.domain.events.EventNames;
import com.dino.infrastructure.serialization.MessageSerializer;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

public class GameController implements Initializable {
    @FXML private Canvas arenaCanvas;
    @FXML private Label timerLabel;
    @FXML private Label roomStatusLabel;
    @FXML private Label threadLabel;
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

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        MainApp.eventBus.subscribe(EventNames.SNAPSHOT_RECEIVED, e -> Platform.runLater(this::refreshUI));
        MainApp.eventBus.subscribe(EventNames.ROOM_RESET, e -> Platform.runLater(() ->
            showFeedback("Sala reiniciada", "#ffadad")));
        MainApp.eventBus.subscribe(EventNames.BUTTON_STATE_CHANGED, e -> Platform.runLater(() ->
            showFeedback(Boolean.TRUE.equals(e.get("pressed")) ? "Boton activado" : "Boton liberado", "#ffe08a")));
        MainApp.eventBus.subscribe(EventNames.PLAYER_REACHED_EXIT, e -> onReachedExitEvent((String) e.get("playerId")));
        MainApp.eventBus.subscribe(EventNames.SCORE_CHANGED, e -> onScoreChangedEvent(e));
        MainApp.eventBus.subscribe(EventNames.GAME_OVER, e -> {
            if (MainApp.sessionService.isHost()) broadcastGameOver();
            Platform.runLater(this::onGameOver);
        });

        feedbackLabel.setVisible(false);
        installMouseHandlers();
        startGameLoop();
    }

    private void installMouseHandlers() {
        arenaCanvas.setOnMousePressed(event -> handleMouseInput(event.getButton(), event.getX(), event.getY()));
        arenaCanvas.setOnMouseDragged(event -> {
            if (event.isPrimaryButtonDown()) {
                handleMouseInput(MouseButton.PRIMARY, event.getX(), event.getY());
            }
        });
    }

    private void handleMouseInput(MouseButton button, double canvasX, double canvasY) {
        Point2D world = canvasToWorld(canvasX, canvasY);
        boolean jump = button == MouseButton.SECONDARY;
        if (button == MouseButton.PRIMARY) {
            Player local = getLocalPlayerSnapshot();
            if (local != null && world.getY() < local.getY() - GameConfig.MOUSE_JUMP_VERTICAL_THRESHOLD) {
                jump = true;
            }
        }
        sendInput(world.getX(), jump);
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

                if (MainApp.udpPeer != null && MainApp.udpPeer.isBound()) pollIncomingMessages();

                if (MainApp.sessionService.isHost() && MainApp.hostMatchService != null) {
                    MainApp.hostMatchService.tick(dt);
                    snapshotTimer += dt;
                    if (snapshotTimer >= (1.0 / GameConfig.SNAPSHOT_RATE_HZ)) {
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

    private void sendInput(double targetX, boolean jumpRequested) {
        if (MainApp.sessionService.isHost()) {
            if (MainApp.hostMatchService != null) {
                MainApp.hostMatchService.handleInput(MainApp.sessionService.getLocalPlayerId(), targetX, jumpRequested);
            }
            return;
        }

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
                boolean jump = Boolean.TRUE.equals(msg.get("jump"));
                if (playerId != null && targetX != null) {
                    MainApp.sessionService.registerPeerAddress(playerId, sender);
                    MainApp.hostMatchService.handleInput(playerId, targetX.doubleValue(), jump);
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
            if (MessageSerializer.GAME_OVER.equals(messageType)) Platform.runLater(this::onGameOver);
        }
    }

    private void broadcastGameOver() {
        if (MainApp.udpPeer == null || !MainApp.udpPeer.isBound()) return;
        Map<String, Object> payload = MainApp.sessionService.getSnapshotData();
        payload.put("type", MessageSerializer.GAME_OVER);
        MainApp.udpPeer.broadcast(payload, MainApp.sessionService.getRemotePeerAddresses());
    }

    private void render() {
        double width = arenaCanvas.getWidth();
        double height = arenaCanvas.getHeight();
        double viewportW = getViewportWorldWidth();
        double viewportH = getViewportWorldHeight();
        double scaleX = width / viewportW;
        double scaleY = height / viewportH;

        GraphicsContext gc = arenaCanvas.getGraphicsContext2D();
        gc.setFill(Color.web("#101522"));
        gc.fillRect(0, 0, width, height);

        drawBackgroundGrid(gc, width, height, scaleX, scaleY);

        ExitZone exitZone = MainApp.sessionService.getExitZoneSnapshot();
        if (exitZone != null) {
            double x = worldToScreenX(exitZone.getX(), scaleX);
            double y = worldToScreenY(exitZone.getY(), scaleY);
            gc.setFill(Color.web("#274c77"));
            gc.fillRect(x, y, exitZone.getWidth() * scaleX, exitZone.getHeight() * scaleY);
            gc.setStroke(Color.web("#5fa8d3"));
            gc.setLineWidth(3);
            gc.strokeRect(x, y, exitZone.getWidth() * scaleX, exitZone.getHeight() * scaleY);
            gc.setFill(Color.WHITE);
            gc.setFont(Font.font("Arial", FontWeight.BOLD, 14));
            gc.fillText("SALIDA", x + 18, y + 24);
        }

        for (PlatformTile platform : MainApp.sessionService.getPlatformsSnapshot()) {
            double x = worldToScreenX(platform.getX(), scaleX);
            double y = worldToScreenY(platform.getY(), scaleY);
            gc.setFill(Color.web("#58657c"));
            gc.fillRoundRect(x, y, platform.getWidth() * scaleX, platform.getHeight() * scaleY, 12, 12);
            gc.setStroke(Color.web("#8d99ae"));
            gc.strokeRoundRect(x, y, platform.getWidth() * scaleX, platform.getHeight() * scaleY, 12, 12);
        }

        ButtonSwitch button = MainApp.sessionService.getButtonSwitchSnapshot();
        if (button != null) {
            gc.setFill(button.isPressed() ? Color.web("#ffd166") : Color.web("#8d6a9f"));
            gc.fillRoundRect(worldToScreenX(button.getX(), scaleX), worldToScreenY(button.getY(), scaleY),
                button.getWidth() * scaleX, button.getHeight() * scaleY, 10, 10);
        }

        Door door = MainApp.sessionService.getDoorSnapshot();
        if (door != null && !door.isOpen()) {
            gc.setFill(Color.web("#be95c4"));
            gc.fillRoundRect(worldToScreenX(door.getX(), scaleX), worldToScreenY(door.getY(), scaleY),
                door.getWidth() * scaleX, door.getHeight() * scaleY, 14, 14);
        }

        List<Player> players = MainApp.sessionService.getPlayersSnapshot().stream()
            .filter(Player::isConnected)
            .sorted(Comparator.comparingDouble(Player::getX))
            .toList();

        gc.setStroke(Color.web("#80ed9988"));
        gc.setLineWidth(4);
        for (int i = 0; i < players.size() - 1; i++) {
            Player a = players.get(i);
            Player b = players.get(i + 1);
            gc.strokeLine(
                worldToScreenX(a.getCenterX(), scaleX),
                worldToScreenY(a.getCenterY(), scaleY),
                worldToScreenX(b.getCenterX(), scaleX),
                worldToScreenY(b.getCenterY(), scaleY)
            );
        }

        Player localPlayer = getLocalPlayerSnapshot();
        for (Player player : players) {
            Color base = GameConfig.COLORS.getOrDefault(player.getColor(), Color.WHITE);
            double x = worldToScreenX(player.getX(), scaleX);
            double y = worldToScreenY(player.getY(), scaleY);
            double drawW = player.getWidth() * scaleX;
            double drawH = player.getHeight() * scaleY;

            gc.setFill(player.isAtExit() ? base.brighter() : base);
            gc.fillRoundRect(x, y, drawW, drawH, 18, 18);
            gc.setStroke(localPlayer != null && player.getId().equals(localPlayer.getId()) ? Color.WHITE : base.darker());
            gc.setLineWidth(localPlayer != null && player.getId().equals(localPlayer.getId()) ? 4 : 2);
            gc.strokeRoundRect(x, y, drawW, drawH, 18, 18);

            gc.setFill(Color.WHITE);
            gc.setFont(Font.font("Arial", FontWeight.BOLD, Math.max(11, 12 * currentZoom)));
            gc.fillText(player.getName() + " · " + player.getScore(), x - 2, y - 10);

            if (localPlayer != null && player.getId().equals(localPlayer.getId())) {
                double targetX = worldToScreenX(player.getTargetX(), scaleX);
                gc.setStroke(Color.web("#ffffff66"));
                gc.setLineWidth(2);
                gc.strokeLine(worldToScreenX(player.getCenterX(), scaleX), y + drawH + 6, targetX, y + drawH + 6);
            }
        }
    }

    private void drawBackgroundGrid(GraphicsContext gc, double width, double height, double scaleX, double scaleY) {
        gc.setStroke(Color.web("#182033"));
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
        for (Player player : MainApp.scoreBoardObserver.getEntries()) {
            String status = !player.isConnected() ? "desconectado"
                : player.isAtExit() ? "salida"
                : player.isAlive() ? "activo" : "caido";
            playersList.getItems().add(
                player.getName() + " · " + player.getScore() + " pts · " + status + " · caidas " + player.getDeaths());
        }

        eventLog.getItems().setAll(MainApp.eventLogObserver.getEntries());
        Door door = MainApp.sessionService.getDoorSnapshot();
        roomStatusLabel.setText(door != null && door.isOpen()
            ? "Puerta abierta: todos pueden pasar"
            : "Click izquierdo mueve · click derecho salta · abre la puerta con el boton");
        threadLabel.setText("Hilo maximo: " + (int) GameConfig.THREAD_MAX_DISTANCE + " px");
    }

    private void updateTimer() {
        timerLabel.setText(String.format("Tiempo: %.1fs", MainApp.sessionService.getElapsedTime()));
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

        double targetX = clampCameraX(localPlayer.getCenterX() - getViewportWorldWidth() / 2.0);
        double targetY = clampCameraY(localPlayer.getCenterY() - getViewportWorldHeight() / 2.0);
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
            Platform.runLater(() -> showFeedback("Llegaste a la salida", "#80ed99"));
        }
    }

    private void onScoreChangedEvent(Map<String, Object> payload) {
        String localId = MainApp.sessionService.getLocalPlayerId();
        if (localId == null || !localId.equals(payload.get("playerId"))) return;
        int delta = ((Number) payload.getOrDefault("delta", 0)).intValue();
        String sign = delta >= 0 ? "+" : "";
        Platform.runLater(() -> showFeedback(sign + delta + " puntos", delta >= 0 ? "#8be9fd" : "#ffadad"));
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

    private Point2D canvasToWorld(double canvasX, double canvasY) {
        double scaleX = arenaCanvas.getWidth() / getViewportWorldWidth();
        double scaleY = arenaCanvas.getHeight() / getViewportWorldHeight();
        double worldX = cameraX + (canvasX / scaleX);
        double worldY = cameraY + (canvasY / scaleY);
        return new Point2D(worldX, worldY);
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
}
