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

import java.net.InetAddress;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

public class GameController implements Initializable {
    @FXML private Canvas arenaCanvas;
    @FXML private ListView<String> scoreBoard;
    @FXML private ListView<String> eventLog;
    @FXML private Label timerLabel;

    private final MessageSerializer serializer = new MessageSerializer();
    private AnimationTimer gameLoop;
    private long lastNano = 0;
    private double snapshotTimer = 0;
    private static final double SNAPSHOT_INTERVAL = 1.0 / 10;

    private static final String[] PLAYER_COLORS = {"#e94560", "#4fc3f7", "#81c784", "#ffb74d"};

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        MainApp.eventBus.subscribe(EventNames.SNAPSHOT_RECEIVED, e -> Platform.runLater(this::refreshUI));
        MainApp.eventBus.subscribe(EventNames.GAME_OVER, e -> Platform.runLater(this::onGameOver));

        arenaCanvas.setOnMouseClicked(ev -> sendInput(ev.getX(), ev.getY()));

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
                if (MainApp.udpPeer != null && MainApp.udpPeer.isBound()) {
                    var received = MainApp.udpPeer.receive();
                    received.ifPresent(entry -> {
                        Map<String, Object> msg = entry.getKey();
                        if (MessageSerializer.SNAPSHOT.equals(msg.get("type"))) {
                            MainApp.sessionService.updateFromSnapshot(msg);
                        }
                    });
                }

                // Host tick + broadcast
                if (MainApp.sessionService.isHost() && MainApp.hostMatchService != null) {
                    MainApp.hostMatchService.tick(dt);
                    snapshotTimer += dt;
                    if (snapshotTimer >= SNAPSHOT_INTERVAL) {
                        snapshotTimer = 0;
                        Map<String, Object> snapshot = MainApp.sessionService.getSnapshotData();
                        snapshot.put("type", MessageSerializer.SNAPSHOT);
                        MainApp.sessionService.updateFromSnapshot(snapshot);
                    }
                }

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

    private void render() {
        double w = arenaCanvas.getWidth();   // fixed 900 from FXML
        double h = arenaCanvas.getHeight();  // fixed 620 from FXML

        GraphicsContext gc = arenaCanvas.getGraphicsContext2D();
        gc.setFill(Color.web("#0d0d1a")); gc.fillRect(0, 0, w, h);
        gc.setStroke(Color.web("#333366")); gc.setLineWidth(3);
        gc.strokeRect(2, 2, w - 4, h - 4);

        // Map world coords (ARENA_X..ARENA_X+ARENA_W) to canvas pixels
        double scaleX = w / com.dino.config.GameConfig.ARENA_W;
        double scaleY = h / com.dino.config.GameConfig.ARENA_H;
        double offX   = -com.dino.config.GameConfig.ARENA_X * scaleX;
        double offY   = -com.dino.config.GameConfig.ARENA_Y * scaleY;

        for (PenaltyZone zone : MainApp.sessionService.getPenaltyZones()) {
            double sx = zone.getX() * scaleX + offX, sy = zone.getY() * scaleY + offY;
            double sr = zone.getRadius() * Math.min(scaleX, scaleY);
            gc.setFill(Color.web("#e9456030")); gc.fillOval(sx - sr, sy - sr, sr * 2, sr * 2);
            gc.setStroke(Color.web("#e94560")); gc.setLineWidth(1.5);
            gc.strokeOval(sx - sr, sy - sr, sr * 2, sr * 2);
        }

        for (CollectibleItem item : MainApp.sessionService.getItems()) {
            if (!item.isActive()) continue;
            double sx = item.getX() * scaleX + offX, sy = item.getY() * scaleY + offY;
            gc.setFill(Color.web("#f5a623")); gc.fillRect(sx - 10, sy - 10, 20, 20);
            gc.setFill(Color.WHITE); gc.setFont(Font.font(10));
            gc.fillText("+" + item.getPoints(), sx - 8, sy + 4);
        }

        int ci = 0;
        for (Player p : MainApp.sessionService.getPlayers().values()) {
            if (!p.isConnected()) continue;
            double sx = p.getX() * scaleX + offX, sy = p.getY() * scaleY + offY;
            gc.setFill(Color.web(PLAYER_COLORS[ci % PLAYER_COLORS.length]));
            gc.fillOval(sx - 18, sy - 18, 36, 36);
            gc.setFill(Color.WHITE); gc.setFont(Font.font(11));
            gc.fillText(p.getName(), sx - 20, sy - 22);
            gc.fillText(String.valueOf(p.getScore()), sx - 6, sy + 5);
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
            .forEach(p -> scoreBoard.getItems().add(p.getName() + ": " + p.getScore()));
        eventLog.getItems().clear();
        eventLog.getItems().addAll(MainApp.eventLogObserver.getEntries());
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
}
