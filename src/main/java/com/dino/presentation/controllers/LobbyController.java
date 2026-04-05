package com.dino.presentation.controllers;

import com.dino.MainApp;
import com.dino.application.services.HostMatchService;
import com.dino.domain.entities.Player;
import com.dino.domain.events.EventNames;
import com.dino.infrastructure.serialization.MessageSerializer;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;

import java.net.InetAddress;
import java.net.URL;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Timer;
import java.util.TimerTask;

public class LobbyController implements Initializable {
    @FXML private ListView<String> playersList;
    @FXML private Button startBtn;
    @FXML private Label statusLabel;

    private final MessageSerializer serializer = new MessageSerializer();
    private Timer networkTimer;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        startBtn.setVisible(MainApp.sessionService.isHost());

        MainApp.eventBus.subscribe(EventNames.PLAYER_JOINED,    e -> refreshPlayerList());
        MainApp.eventBus.subscribe(EventNames.PLAYER_READY,     e -> refreshPlayerList());
        MainApp.eventBus.subscribe(EventNames.SNAPSHOT_RECEIVED, e -> refreshPlayerList());

        refreshPlayerList();

        if (!MainApp.sessionService.isHost()) startNetworkLoop();
    }

    private void refreshPlayerList() {
        Platform.runLater(() -> {
            playersList.getItems().clear();
            for (Player p : MainApp.sessionService.getPlayers().values()) {
                playersList.getItems().add(p.getName() + (p.isConnected() ? " ✓" : " ..."));
            }
        });
    }

    @FXML
    public void onListo() {
        if (MainApp.sessionService.isHost()) {
            statusLabel.setText("Host listo. Esperando más jugadores...");
        } else {
            try {
                Map<String, Object> msg = serializer.build(MessageSerializer.READY,
                    "playerId", MainApp.sessionService.getLocalPlayerId());
                MainApp.udpPeer.send(msg,
                    InetAddress.getByName(MainApp.sessionService.getHostIp()),
                    MainApp.sessionService.getHostPort());
                statusLabel.setText("Listo! Esperando al host...");
            } catch (Exception e) {
                statusLabel.setText("Error: " + e.getMessage());
            }
        }
    }

    @FXML
    public void onIniciarPartida() {
        if (!MainApp.sessionService.isHost()) return;
        try {
            HostMatchService hostMatchService = new HostMatchService(MainApp.sessionService, MainApp.eventBus);
            MainApp.hostMatchService = hostMatchService;
            hostMatchService.initWorld();
            MainApp.eventBus.publish(EventNames.GAME_STARTED, Map.of());
            if (networkTimer != null) networkTimer.cancel();
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/dino/views/game.fxml"));
            Scene scene = new Scene(loader.load(), 1280, 780);
            MainApp.getStage().setScene(scene);
        } catch (Exception e) {
            statusLabel.setText("Error: " + e.getMessage());
        }
    }

    private void startNetworkLoop() {
        networkTimer = new Timer(true);
        networkTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (MainApp.udpPeer == null || !MainApp.udpPeer.isBound()) return;
                var received = MainApp.udpPeer.receive();
                received.ifPresent(entry -> {
                    Map<String, Object> msg = entry.getKey();
                    String type = (String) msg.get("type");
                    if (MessageSerializer.START_GAME.equals(type)) {
                        Platform.runLater(() -> {
                            try {
                                if (networkTimer != null) networkTimer.cancel();
                                MainApp.eventBus.publish(EventNames.GAME_STARTED, Map.of());
                                FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/dino/views/game.fxml"));
                                Scene scene = new Scene(loader.load(), 1280, 780);
                                MainApp.getStage().setScene(scene);
                            } catch (Exception e) {
                                statusLabel.setText("Error: " + e.getMessage());
                            }
                        });
                    } else if (MessageSerializer.LOBBY_SNAPSHOT.equals(type)) {
                        MainApp.sessionService.updateFromSnapshot(msg);
                    }
                });
            }
        }, 0, 100);
    }
}
