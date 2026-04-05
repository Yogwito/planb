package com.dino.presentation.controllers;

import com.dino.MainApp;
import com.dino.application.usecases.CreateSessionUseCase;
import com.dino.application.usecases.JoinSessionUseCase;
import com.dino.infrastructure.network.UdpPeer;
import com.dino.infrastructure.serialization.MessageSerializer;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * Controlador de la pantalla inicial.
 *
 * <p>Recoge el nombre del jugador y los parámetros básicos de red. Desde aquí
 * la instancia local decide si actuará como host o como cliente y luego abre el
 * lobby correspondiente.</p>
 */
public class StartMenuController implements Initializable {
    @FXML private TextField playerNameField;
    @FXML private RadioButton createRadio;
    @FXML private RadioButton joinRadio;
    @FXML private ToggleGroup modeToggle;
    @FXML private TextField localIpField;
    @FXML private TextField localPortField;
    @FXML private TextField hostIpField;
    @FXML private TextField hostPortField;
    @FXML private HBox expectedPlayersBox;
    @FXML private ChoiceBox<Integer> expectedPlayersChoice;
    @FXML private Label errorLabel;

    /**
     * Configura los controles iniciales y alterna campos según el modo
     * seleccionado.
     */
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        expectedPlayersChoice.getItems().addAll(2, 3, 4);
        expectedPlayersChoice.setValue(2);

        modeToggle.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            boolean isCreate = newT == createRadio;
            expectedPlayersBox.setVisible(isCreate);
            hostIpField.setDisable(isCreate);
            hostPortField.setDisable(isCreate);
        });

        hostIpField.setDisable(true);
        hostPortField.setDisable(true);
    }

    /**
     * Valida la información del formulario, crea la sesión y abre el lobby.
     *
     * <p>Si se elige "Crear sala", inicializa la instancia local como host. Si
     * se elige "Unirse", envía un primer {@code JOIN} al host remoto.</p>
     */
    @FXML
    public void onAbrirLobby() {
        errorLabel.setVisible(false);
        String name = playerNameField.getText().trim();
        if (name.isEmpty()) { showError("Ingresa tu nombre de jugador."); return; }

        String localIp = localIpField.getText().trim();
        int localPort;
        try {
            localPort = Integer.parseInt(localPortField.getText().trim());
        } catch (NumberFormatException e) { showError("Puerto local inválido."); return; }

        UdpPeer udpPeer = new UdpPeer();
        MessageSerializer serializer = new MessageSerializer();

        try {
            if (createRadio.isSelected()) {
                int expected = expectedPlayersChoice.getValue();
                new CreateSessionUseCase(MainApp.sessionService, udpPeer, MainApp.eventBus)
                    .execute(name, localIp, localPort, expected);
            } else {
                String hostIp = hostIpField.getText().trim();
                if (hostIp.isEmpty()) { showError("Ingresa la IP del host."); return; }
                int hostPort;
                try {
                    hostPort = Integer.parseInt(hostPortField.getText().trim());
                } catch (NumberFormatException e) { showError("Puerto host inválido."); return; }
                new JoinSessionUseCase(MainApp.sessionService, udpPeer, serializer, MainApp.eventBus)
                    .execute(name, localIp, localPort, hostIp, hostPort);
            }

            MainApp.udpPeer = udpPeer;
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/dino/views/lobby.fxml"));
            Scene scene = new Scene(loader.load(), 1280, 780);
            MainApp.getStage().setScene(scene);
        } catch (Exception e) {
            showError("Error al conectar: " + e.getMessage());
        }
    }

    /**
     * Muestra un mensaje de error en la misma pantalla.
     *
     * @param msg texto visible para el usuario
     */
    private void showError(String msg) {
        errorLabel.setText(msg);
        errorLabel.setVisible(true);
    }
}
