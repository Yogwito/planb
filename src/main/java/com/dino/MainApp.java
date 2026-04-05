package com.dino;

import com.dino.application.services.EventBus;
import com.dino.application.services.HostMatchService;
import com.dino.application.services.SessionService;
import com.dino.infrastructure.audio.SoundManager;
import com.dino.infrastructure.network.UdpPeer;
import com.dino.infrastructure.serialization.MessageSerializer;
import com.dino.presentation.components.EventLogObserver;
import com.dino.presentation.components.ScoreBoardObserver;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.net.InetAddress;
import java.util.Map;

public class MainApp extends Application {

    public static EventBus eventBus;
    public static SessionService sessionService;
    public static SoundManager soundManager;
    public static ScoreBoardObserver scoreBoardObserver;
    public static EventLogObserver eventLogObserver;
    public static UdpPeer udpPeer;
    public static HostMatchService hostMatchService;

    private static Stage primaryStage;

    @Override
    public void start(Stage stage) throws Exception {
        primaryStage = stage;
        resetRuntimeState();

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/dino/views/start_menu.fxml"));
        Scene scene = new Scene(loader.load(), 1280, 780);
        stage.setTitle("Threaded");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.setOnCloseRequest(event -> shutdownNetworking());

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        stage.setX((screen.getWidth()  - 1280) / 2);
        stage.setY((screen.getHeight() - 780)  / 2);

        stage.show();
    }

    public static Stage getStage() { return primaryStage; }

    public static void resetRuntimeState() {
        shutdownNetworking();
        if (soundManager != null) soundManager.close();
        eventBus           = new EventBus();
        sessionService     = new SessionService(eventBus);
        soundManager       = new SoundManager(eventBus);
        scoreBoardObserver = new ScoreBoardObserver(eventBus);
        eventLogObserver   = new EventLogObserver(eventBus);
        udpPeer            = null;
        hostMatchService   = null;
    }

    private static void sendDisconnectIfNeeded() {
        if (udpPeer == null || sessionService == null || !udpPeer.isBound()) return;
        if (sessionService.isHost()) return;
        String playerId = sessionService.getLocalPlayerId();
        String hostIp = sessionService.getHostIp();
        if (playerId == null || hostIp == null || hostIp.isBlank()) return;

        try {
            Map<String, Object> msg = new MessageSerializer().build(
                MessageSerializer.DISCONNECT,
                "playerId", playerId
            );
            udpPeer.send(msg, InetAddress.getByName(hostIp), sessionService.getHostPort());
        } catch (Exception ignored) {
        }
    }

    private static void shutdownNetworking() {
        sendDisconnectIfNeeded();
        if (udpPeer != null) udpPeer.close();
        udpPeer = null;
        hostMatchService = null;
    }

    public static void main(String[] args) { launch(args); }
}
