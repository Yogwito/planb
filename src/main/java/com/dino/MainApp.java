package com.dino;

import com.dino.application.services.EventBus;
import com.dino.application.services.HostMatchService;
import com.dino.application.services.SessionService;
import com.dino.infrastructure.audio.SoundManager;
import com.dino.infrastructure.network.UdpPeer;
import com.dino.presentation.components.EventLogObserver;
import com.dino.presentation.components.ScoreBoardObserver;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

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
        eventBus          = new EventBus();
        sessionService    = new SessionService(eventBus);
        soundManager      = new SoundManager(eventBus);
        scoreBoardObserver = new ScoreBoardObserver(eventBus);
        eventLogObserver  = new EventLogObserver(eventBus);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/dino/views/start_menu.fxml"));
        Scene scene = new Scene(loader.load(), 1280, 780);
        stage.setTitle("Dino Arena UDP");
        stage.setScene(scene);
        stage.setResizable(false);

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        stage.setX((screen.getWidth()  - 1280) / 2);
        stage.setY((screen.getHeight() - 780)  / 2);

        stage.show();
    }

    public static Stage getStage() { return primaryStage; }

    public static void main(String[] args) { launch(args); }
}
