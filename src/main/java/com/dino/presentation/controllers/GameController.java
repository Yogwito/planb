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
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

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
    private Double pendingTargetX;
    private int pendingJumpRepeats;
    private boolean leftPressed;
    private boolean rightPressed;
    private double timeSinceLastSnapshot;
    private double lastSnapshotAgeForRender;
    private final Map<String, RenderState> renderStates = new HashMap<>();
    private final List<Particle> particles = new ArrayList<>();

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        MainApp.eventBus.subscribe(EventNames.SNAPSHOT_RECEIVED, e -> {
            timeSinceLastSnapshot = 0;
            Platform.runLater(this::refreshUI);
        });
        MainApp.eventBus.subscribe(EventNames.ROOM_RESET, e -> Platform.runLater(() ->
            {
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
                showFeedback("Nivel " + (((Number) e.getOrDefault("levelIndex", 0)).intValue() + 1), "#8be9fd");
                spawnBurst(arenaCanvas.getWidth() * 0.5, 200, Color.web("#8be9fd"), 22);
            }));
        MainApp.eventBus.subscribe(EventNames.PLAYER_JUMPED, e -> onJumpEvent((String) e.get("playerId")));
        MainApp.eventBus.subscribe(EventNames.PLAYER_COLLIDED, this::onCollisionEvent);
        MainApp.eventBus.subscribe(EventNames.THREAD_STRETCHED, this::onThreadEvent);
        MainApp.eventBus.subscribe(EventNames.PLAYER_REACHED_EXIT, e -> onReachedExitEvent((String) e.get("playerId")));
        MainApp.eventBus.subscribe(EventNames.SCORE_CHANGED, e -> onScoreChangedEvent(e));
        MainApp.eventBus.subscribe(EventNames.GAME_OVER, e -> {
            if (MainApp.sessionService.isHost()) broadcastGameOver();
            Platform.runLater(this::onGameOver);
        });

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

        double groundScreenY = worldToScreenY(800, scaleY);
        double groundScreenX = worldToScreenX(0, scaleX);
        double groundScreenW = GameConfig.LEVEL_WIDTH * scaleX;
        gc.setFill(Color.web("#1a1a2e"));
        gc.fillRect(groundScreenX, groundScreenY, groundScreenW, 100 * scaleY);
        gc.setStroke(Color.web("#4a4a6a"));
        gc.setLineWidth(3);
        gc.strokeLine(groundScreenX, groundScreenY, groundScreenX + groundScreenW, groundScreenY);

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
            double tension = Math.max(0.0, Math.min(1.0, (dist - GameConfig.THREAD_MAX_DISTANCE * 0.6) / (GameConfig.THREAD_MAX_DISTANCE * 0.4)));
            Color glowColor = Color.web("#4cc9f0").interpolate(Color.web("#ef476f"), tension);
            gc.setStroke(glowColor.deriveColor(0, 1, 1, 0.27));
            double midX = (a.x + b.x) / 2.0;
            double sag = Math.min(16, Math.abs(b.x - a.x) * 0.05);
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
            double tension = Math.max(0.0, Math.min(1.0, (dist - GameConfig.THREAD_MAX_DISTANCE * 0.6) / (GameConfig.THREAD_MAX_DISTANCE * 0.4)));
            Color threadColor = Color.web("#80ed99").interpolate(Color.web("#ef476f"), tension);
            gc.setStroke(threadColor.deriveColor(0, 1, 1, 0.8));
            double midX = (a.x + b.x) / 2.0;
            double sag = Math.min(16, Math.abs(b.x - a.x) * 0.05);
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
            double stretch = Math.max(-0.18, Math.min(0.22, player.getVy() / 900.0));
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
        gc.setFill(Color.web("#0b1020"));
        gc.fillRect(0, 0, width, height);
        gc.setFill(Color.web("#11213a"));
        gc.fillOval(-120, -80, 420, 260);
        gc.setFill(Color.web("#152844"));
        gc.fillOval(width - 320, -40, 380, 220);
        gc.setFill(Color.web("#0f1b2d"));
        gc.fillRect(0, height * 0.72, width, height * 0.28);
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
        for (Player player : MainApp.scoreBoardObserver.getEntries()) {
            String status = !player.isConnected() ? "desconectado"
                : player.isAtExit() ? "salida"
                : player.isAlive() ? "activo" : "caido";
            playersList.getItems().add(
                player.getName() + " · " + player.getScore() + " pts · " + status + " · caidas " + player.getDeaths());
        }

        eventLog.getItems().setAll(MainApp.eventLogObserver.getEntries());
        Door door = MainApp.sessionService.getDoorSnapshot();
        levelLabel.setText("Nivel " + (MainApp.sessionService.getCurrentLevelIndex() + 1) + "/" + Math.max(1, MainApp.sessionService.getTotalLevels()));
        roomStatusLabel.setText(door != null && door.isOpen()
            ? "Puerta abierta: avancen juntos hacia la salida"
            : switch (MainApp.sessionService.getCurrentLevelIndex()) {
                case 0 -> "Nivel 1: activen el boton y crucen juntos";
                case 1 -> "Nivel 2: mantengan el hilo corto en los saltos";
                default -> "Nivel 3: coordinen el ritmo y no se separen";
            });
        threadLabel.setText("Hilo maximo: " + (int) GameConfig.THREAD_MAX_DISTANCE + " px");
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
        for (Player player : players) {
            RenderState state = renderStates.computeIfAbsent(player.getId(), ignored -> new RenderState(player.getX(), player.getY()));
            if (MainApp.sessionService.isHost()) {
                state.x = player.getX();
                state.y = player.getY();
                state.vx = player.getVx();
                state.vy = player.getVy();
            } else {
                boolean isLocalClientPlayer = player.getId().equals(MainApp.sessionService.getLocalPlayerId());
                double predictionWindow = Math.min(
                    isLocalClientPlayer ? GameConfig.LOCAL_CLIENT_PREDICTION_SECONDS : GameConfig.REMOTE_PREDICTION_SECONDS,
                    lastSnapshotAgeForRender
                );
                double targetX = player.getX() + player.getVx() * predictionWindow;
                double targetY = player.getY() + player.getVy() * predictionWindow;
                double blend = 1.0 - Math.pow(1.0 - GameConfig.REMOTE_SMOOTHING, Math.max(1.0, dt * 60.0));
                state.x += (targetX - state.x) * blend;
                state.y += (targetY - state.y) * blend;
                state.vx += (player.getVx() - state.vx) * blend;
                state.vy += (player.getVy() - state.vy) * blend;
            }
        }
        renderStates.keySet().removeIf(id -> players.stream().noneMatch(player -> player.getId().equals(id)));
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

    private static final class RenderState {
        double x;
        double y;
        double vx;
        double vy;

        RenderState(double x, double y) {
            this.x = x;
            this.y = y;
        }

        double centerX(double width) { return x + width / 2.0; }
        double centerY(double height) { return y + height / 2.0; }
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
