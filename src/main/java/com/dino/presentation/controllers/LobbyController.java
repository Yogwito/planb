package com.dino.presentation.controllers;

import com.dino.MainApp;
import com.dino.application.services.HostMatchService;
import com.dino.config.GameConfig;
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
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Controlador del lobby previo a la partida.
 *
 * <p>Gestiona la espera de jugadores, el estado {@code ready}, el intercambio
 * de snapshots del lobby y la transición hacia la escena principal. Cuando la
 * instancia local es host, también acepta nuevos jugadores y arranca la partida.</p>
 */
public class LobbyController implements Initializable {
    @FXML private ListView<String> playersList;
    @FXML private Button startBtn;
    @FXML private Label statusLabel;

    private final MessageSerializer serializer = new MessageSerializer();
    private Timer networkTimer;
    private long lastLobbyBroadcastAt = 0;
    private long lastHeartbeatAt = 0;
    private boolean startGameHandled = false;

    /**
     * Prepara la lista inicial de jugadores y arranca el loop de red del lobby.
     */
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        startBtn.setVisible(MainApp.sessionService.isHost());

        MainApp.eventBus.subscribe(EventNames.PLAYER_JOINED,    e -> refreshPlayerList());
        MainApp.eventBus.subscribe(EventNames.PLAYER_READY,     e -> refreshPlayerList());
        MainApp.eventBus.subscribe(EventNames.SNAPSHOT_RECEIVED, e -> refreshPlayerList());

        refreshPlayerList();

        startNetworkLoop();
    }

    /**
     * Refresca la lista visible de jugadores usando un snapshot defensivo.
     */
    private void refreshPlayerList() {
        Platform.runLater(() -> {
            playersList.getItems().clear();
            for (Player p : MainApp.sessionService.getPlayersSnapshot()) {
                String state = p.isReady() ? " listo" : (p.isConnected() ? " conectado" : " desconectado");
                playersList.getItems().add(p.getName() + " [" + state + "]");
            }
        });
    }

    /**
     * Marca al jugador local como listo.
     *
     * <p>Si la instancia es host actualiza el estado local y redistribuye el
     * snapshot. Si es cliente, envía un mensaje {@code READY} al host.</p>
     */
    @FXML
    public void onListo() {
        if (MainApp.sessionService.isHost()) {
            MainApp.sessionService.markPlayerReady(MainApp.sessionService.getLocalPlayerId(), true);
            MainApp.eventBus.publish(EventNames.PLAYER_READY, Map.of("playerId", MainApp.sessionService.getLocalPlayerId()));
            broadcastLobbySnapshot();
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

    /**
     * Inicia la partida desde el host.
     *
     * <p>El host crea la simulación autoritativa, genera el primer snapshot del
     * juego y difunde varios {@code START_GAME} para reducir pérdida de paquetes.</p>
     */
    @FXML
    public void onIniciarPartida() {
        if (!MainApp.sessionService.isHost()) return;
        try {
            HostMatchService hostMatchService = new HostMatchService(MainApp.sessionService, MainApp.eventBus);
            MainApp.hostMatchService = hostMatchService;
            hostMatchService.initWorld();
            Map<String, Object> startMessage = MainApp.sessionService.getSnapshotData();
            startMessage.put("type", MessageSerializer.START_GAME);
            MainApp.udpPeer.broadcastBurst(startMessage, MainApp.sessionService.getRemotePeerAddresses(), 6, 55);
            MainApp.eventBus.publish(EventNames.GAME_STARTED, Map.of());
            if (networkTimer != null) networkTimer.cancel();
            openGameScene();
        } catch (Exception e) {
            statusLabel.setText("Error: " + e.getMessage());
        }
    }

    /**
     * Arranca un timer liviano encargado de procesar mensajes del lobby.
     *
     * <p>En host también mantiene snapshots periódicos y expiración de peers; en
     * cliente envía heartbeat para que el host sepa que sigue vivo.</p>
     */
    private void startNetworkLoop() {
        networkTimer = new Timer(true);
        networkTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (MainApp.udpPeer == null || !MainApp.udpPeer.isBound()) return;
                if (MainApp.sessionService.isHost()) {
                    expireInactivePeersIfNeeded();
                    broadcastLobbySnapshotIfDue();
                } else {
                    sendHeartbeatIfDue();
                }
                var received = MainApp.udpPeer.receive();
                received.ifPresent(entry -> handleMessage(entry.getKey(), entry.getValue()));
            }
        }, 0, 100);
    }

    /**
     * Enruta un mensaje entrante al comportamiento de host o cliente.
     *
     * @param msg contenido recibido por UDP
     * @param sender dirección del peer que originó el mensaje
     */
    private void handleMessage(Map<String, Object> msg, InetSocketAddress sender) {
        String type = (String) msg.get("type");
        if (type == null) return;

        if (MainApp.sessionService.isHost()) {
            handleHostMessage(type, msg, sender);
            return;
        }

        if (MessageSerializer.START_GAME.equals(type)) {
            if (startGameHandled) return;
            startGameHandled = true;
            MainApp.sessionService.updateFromSnapshot(msg);
            Platform.runLater(() -> {
                try {
                    if (networkTimer != null) networkTimer.cancel();
                    MainApp.eventBus.publish(EventNames.GAME_STARTED, Map.of());
                    openGameScene();
                } catch (Exception e) {
                    statusLabel.setText("Error: " + e.getMessage());
                }
            });
        } else if (MessageSerializer.LOBBY_SNAPSHOT.equals(type)) {
            MainApp.sessionService.updateFromSnapshot(msg);
        }
    }

    /**
     * Lógica exclusiva del host para el lobby.
     *
     * <p>Desde aquí se aceptan nuevos jugadores, se registran heartbeats y se
     * eliminan peers desconectados.</p>
     */
    private void handleHostMessage(String type, Map<String, Object> msg, InetSocketAddress sender) {
        if (MessageSerializer.JOIN.equals(type)) {
            String playerId = (String) msg.get("playerId");
            String name = (String) msg.getOrDefault("name", "Jugador");
            if (playerId == null || playerId.isBlank()) return;

            Player player = MainApp.sessionService.getPlayers().get(playerId);
            if (player == null) {
                player = new Player(playerId, name, nextPlayerColor());
                MainApp.sessionService.addPlayer(player);
            } else {
                player.setName(name);
                player.setConnected(true);
            }
            MainApp.sessionService.registerPeerAddress(playerId, sender);
            MainApp.sessionService.markPeerSeen(playerId);
            MainApp.eventBus.publish(EventNames.PLAYER_JOINED, Map.of("playerId", playerId, "name", name));
            broadcastLobbySnapshot();
        } else if (MessageSerializer.READY.equals(type)) {
            String playerId = (String) msg.get("playerId");
            MainApp.sessionService.registerPeerAddress(playerId, sender);
            MainApp.sessionService.markPeerSeen(playerId);
            MainApp.sessionService.markPlayerConnected(playerId, true);
            MainApp.sessionService.markPlayerReady(playerId, true);
            MainApp.eventBus.publish(EventNames.PLAYER_READY, msg);
            Platform.runLater(() -> statusLabel.setText("Jugador listo: " + msg.getOrDefault("playerId", "?")));
            broadcastLobbySnapshot();
        } else if (MessageSerializer.HEARTBEAT.equals(type)) {
            String playerId = (String) msg.get("playerId");
            MainApp.sessionService.registerPeerAddress(playerId, sender);
            MainApp.sessionService.markPeerSeen(playerId);
            MainApp.sessionService.markPlayerConnected(playerId, true);
        } else if (MessageSerializer.DISCONNECT.equals(type)) {
            String playerId = (String) msg.get("playerId");
            MainApp.sessionService.removePlayer(playerId);
            Platform.runLater(() -> statusLabel.setText("Jugador desconectado: " + msg.getOrDefault("playerId", "?")));
            broadcastLobbySnapshot();
        }
    }

    /**
     * Mantiene vivo al cliente dentro del lobby cuando aún no hay partida.
     */
    private void sendHeartbeatIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastHeartbeatAt < (long) (GameConfig.CLIENT_HEARTBEAT_SECONDS * 1000)) return;
        lastHeartbeatAt = now;
        try {
            Map<String, Object> msg = serializer.build(MessageSerializer.HEARTBEAT,
                "playerId", MainApp.sessionService.getLocalPlayerId());
            MainApp.udpPeer.send(msg,
                InetAddress.getByName(MainApp.sessionService.getHostIp()),
                MainApp.sessionService.getHostPort());
        } catch (Exception ignored) {
        }
    }

    /**
     * Elimina peers inactivos del lobby cuando superan el timeout configurado.
     */
    private void expireInactivePeersIfNeeded() {
        List<String> expired = MainApp.sessionService.expireInactivePeers((long) (GameConfig.HOST_PEER_TIMEOUT_SECONDS * 1000));
        if (!expired.isEmpty()) broadcastLobbySnapshot();
    }

    /**
     * Limita la frecuencia de snapshots del lobby para no saturar la red.
     */
    private void broadcastLobbySnapshotIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastLobbyBroadcastAt < 250) return;
        broadcastLobbySnapshot();
        lastLobbyBroadcastAt = now;
    }

    /**
     * Envía el estado completo del lobby a todos los clientes remotos.
     */
    private void broadcastLobbySnapshot() {
        List<InetSocketAddress> remotes = MainApp.sessionService.getRemotePeerAddresses();
        if (remotes.isEmpty()) return;
        Map<String, Object> snapshot = MainApp.sessionService.getSnapshotData();
        snapshot.put("type", MessageSerializer.LOBBY_SNAPSHOT);
        MainApp.udpPeer.broadcast(snapshot, remotes);
    }

    /**
     * Asigna colores predeterminados a nuevos jugadores según el orden de llegada.
     */
    private String nextPlayerColor() {
        String[] colors = {"red", "blue", "green", "yellow"};
        int index = Math.max(0, MainApp.sessionService.getPlayers().size()) % colors.length;
        return colors[index];
    }

    /**
     * Cambia la escena actual hacia la vista de juego.
     *
     * @throws Exception si falla la carga del FXML
     */
    private void openGameScene() throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/dino/views/game.fxml"));
        Scene scene = new Scene(loader.load(), 1280, 780);
        MainApp.getStage().setScene(scene);
    }
}
