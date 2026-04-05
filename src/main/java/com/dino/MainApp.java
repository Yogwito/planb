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

/**
 * Punto de entrada de la aplicación JavaFX.
 *
 * <p>Su responsabilidad es inicializar los servicios globales que comparten
 * todas las escenas del juego, abrir la primera vista y coordinar el cierre
 * limpio de la red UDP y del audio. En este proyecto se usa como un pequeño
 * bootstrapper: no contiene reglas del juego, solo orquesta la infraestructura
 * principal y el cambio de escenas.</p>
 *
 * <p>Participa tanto del lado host como del lado cliente porque ambas
 * instancias crean los mismos servicios base y luego se especializan según la
 * opción elegida en el menú inicial.</p>
 */
public class MainApp extends Application {

    /** Bus de eventos global usado para desacoplar UI, audio y lógica. */
    public static EventBus eventBus;
    /** Estado compartido de la sesión actual, replicado por snapshots. */
    public static SessionService sessionService;
    /** Componente de audio que escucha eventos del juego. */
    public static SoundManager soundManager;
    /** Observador que resume información del ranking. */
    public static ScoreBoardObserver scoreBoardObserver;
    /** Observador que mantiene el log textual de eventos. */
    public static EventLogObserver eventLogObserver;
    /** Socket UDP activo de la instancia local. */
    public static UdpPeer udpPeer;
    /** Simulación autoritativa cuando esta instancia actúa como host. */
    public static HostMatchService hostMatchService;

    private static Stage primaryStage;

    /**
     * Inicializa la aplicación gráfica y abre la pantalla inicial.
     *
     * @param stage escenario principal de JavaFX
     * @throws Exception si ocurre un error al cargar la vista inicial
     */
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

    /**
     * Retorna el escenario principal para reutilizarlo desde los controladores.
     *
     * @return escenario principal de JavaFX
     */
    public static Stage getStage() { return primaryStage; }

    /**
     * Reconstruye el estado global de la aplicación desde cero.
     *
     * <p>Se usa al iniciar la app, al volver al menú y al terminar una partida.
     * Primero libera red/audio previos y luego crea servicios nuevos para evitar
     * arrastrar listeners, sockets o estado compartido de una sesión anterior.</p>
     */
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

    /**
     * Si esta instancia es cliente, intenta avisar al host que se desconecta.
     *
     * <p>Es un cierre oportunista: si el paquete UDP no llega, el host igual
     * terminará limpiando el peer por timeout.</p>
     */
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

    /**
     * Cierra el socket UDP actual y elimina referencias al loop del host.
     *
     * <p>Este método no destruye el resto de servicios; solo corta la red
     * activa de la sesión en curso.</p>
     */
    private static void shutdownNetworking() {
        sendDisconnectIfNeeded();
        if (udpPeer != null) udpPeer.close();
        udpPeer = null;
        hostMatchService = null;
    }

    /**
     * Punto de arranque estándar de JavaFX.
     *
     * @param args argumentos de línea de comandos
     */
    public static void main(String[] args) { launch(args); }
}
