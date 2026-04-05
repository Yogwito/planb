package com.dino.presentation.controllers;

import com.dino.MainApp;
import com.dino.config.GameConfig;
import com.dino.domain.entities.Player;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ResourceBundle;

public class GameOverController implements Initializable {
    @FXML private TableView<Player> resultsTable;
    @FXML private TableColumn<Player, String> posColumn;
    @FXML private TableColumn<Player, String> nameColumn;
    @FXML private TableColumn<Player, String> scoreColumn;
    @FXML private Label winnerLabel;
    @FXML private Label totalTimeLabel;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        List<Player> sorted = new ArrayList<>(MainApp.sessionService.getPlayersSnapshot());
        sorted.sort(Comparator
            .comparingInt(Player::getScore).reversed()
            .thenComparingInt(player -> player.getFinishOrder() == 0 ? Integer.MAX_VALUE : player.getFinishOrder())
            .thenComparingInt(Player::getDeaths));

        posColumn.setCellValueFactory(data ->
            new SimpleStringProperty("#" + (sorted.indexOf(data.getValue()) + 1)));
        nameColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getName()));
        scoreColumn.setCellValueFactory(data -> new SimpleStringProperty(
            data.getValue().getScore() + " pts · caidas " + data.getValue().getDeaths()));

        resultsTable.getItems().addAll(sorted);
        if (!sorted.isEmpty()) {
            Player winner = sorted.get(0);
            winnerLabel.setText("Ganador: " + winner.getName() + " con " + winner.getScore() + " pts");
        } else {
            winnerLabel.setText("Ganador: —");
        }
        totalTimeLabel.setText(String.format("Tiempo total: %.1fs", MainApp.sessionService.getElapsedTime()));
    }

    @FXML
    public void onVolverAlMenu() {
        MainApp.resetRuntimeState();
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/dino/views/start_menu.fxml"));
            Scene scene = new Scene(loader.load(), GameConfig.WINDOW_WIDTH, GameConfig.WINDOW_HEIGHT);
            MainApp.getStage().setScene(scene);
        } catch (Exception e) {
            System.err.println("[GameOverController] Error: " + e.getMessage());
        }
    }
}
