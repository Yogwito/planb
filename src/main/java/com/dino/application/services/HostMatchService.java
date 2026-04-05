package com.dino.application.services;

import com.dino.config.GameConfig;
import com.dino.domain.entities.ButtonSwitch;
import com.dino.domain.entities.Door;
import com.dino.domain.entities.ExitZone;
import com.dino.domain.entities.PlatformTile;
import com.dino.domain.entities.Player;
import com.dino.domain.events.EventNames;
import com.dino.domain.rules.GameRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HostMatchService {
    private final SessionService sessionService;
    private final EventBus eventBus;
    private final Map<String, InputState> playerInputs = new HashMap<>();
    private boolean gameOver = false;
    private boolean buttonScoreAwarded = false;
    private int nextFinishOrder = 1;

    public HostMatchService(SessionService sessionService, EventBus eventBus) {
        this.sessionService = sessionService;
        this.eventBus = eventBus;
    }

    public void initWorld() {
        sessionService.getPlatforms().clear();
        sessionService.getSpawnPoints().clear();

        sessionService.getPlatforms().add(new PlatformTile("ground", 0, 820, GameConfig.LEVEL_WIDTH, 80));
        sessionService.getPlatforms().add(new PlatformTile("left_step", 180, 690, 220, 24));
        sessionService.getPlatforms().add(new PlatformTile("middle_step", 520, 610, 240, 24));
        sessionService.getPlatforms().add(new PlatformTile("button_ledge", 880, 680, 180, 24));
        sessionService.getPlatforms().add(new PlatformTile("door_ledge", 1210, 560, 210, 24));
        sessionService.getPlatforms().add(new PlatformTile("exit_ledge", 1500, 470, 220, 24));

        sessionService.getSpawnPoints().add(new double[]{90, 740});
        sessionService.getSpawnPoints().add(new double[]{150, 740});
        sessionService.getSpawnPoints().add(new double[]{210, 740});
        sessionService.getSpawnPoints().add(new double[]{270, 740});

        ButtonSwitch button = new ButtonSwitch("button", 930, 664, GameConfig.BUTTON_WIDTH, GameConfig.BUTTON_HEIGHT);
        Door door = new Door("door", 1320, 412, GameConfig.DOOR_WIDTH, GameConfig.DOOR_HEIGHT);
        ExitZone exitZone = new ExitZone(1560, 360, GameConfig.EXIT_WIDTH, GameConfig.EXIT_HEIGHT);

        sessionService.setButtonSwitch(button);
        sessionService.setDoor(door);
        sessionService.setExitZone(exitZone);
        sessionService.setElapsedTime(0);
        sessionService.setGameRunning(true);
        sessionService.setRoomResetCount(0);
        sessionService.setRoomResetReason("");
        playerInputs.clear();
        gameOver = false;
        buttonScoreAwarded = false;
        nextFinishOrder = 1;

        resetRoomState();
    }

    public void handleInput(String playerId, double targetX, boolean jumpPressed) {
        InputState state = playerInputs.computeIfAbsent(playerId, ignored -> new InputState());
        state.targetX = targetX;
        state.hasTarget = true;
        if (jumpPressed) state.jumpQueued = true;
    }

    public void tick(double dt) {
        if (gameOver || !sessionService.isGameRunning()) return;
        sessionService.setElapsedTime(sessionService.getElapsedTime() + dt);

        List<Player> players = new ArrayList<>(sessionService.getPlayers().values());
        for (Player player : players) {
            if (!player.isConnected()) continue;
            updatePlayer(player, dt);
        }

        updateButtonAndDoor(players);
        updateExitState(players);

        for (Player player : players) {
            if (player.isConnected() && player.isAlive() && player.getY() > GameConfig.FALL_RESET_Y) {
                player.setAlive(false);
                player.setDeaths(player.getDeaths() + 1);
                awardScore(player, -GameConfig.SCORE_FALL_PENALTY, "cayo al vacio");
                eventBus.publish(EventNames.PLAYER_DIED, Map.of("playerId", player.getId()));
                resetRoom("Caida al vacio");
                return;
            }
        }

        if (GameRules.allConnectedPlayersAtExit(players)) {
            gameOver = true;
            sessionService.setGameRunning(false);
            eventBus.publish(EventNames.LEVEL_COMPLETED, Map.of("elapsedTime", sessionService.getElapsedTime()));
            eventBus.publish(EventNames.GAME_OVER, Map.of("elapsedTime", sessionService.getElapsedTime()));
        }
    }

    private void updatePlayer(Player player, double dt) {
        InputState input = playerInputs.computeIfAbsent(player.getId(), ignored -> new InputState());
        double previousX = player.getX();
        double previousY = player.getY();
        double previousVx = player.getVx();
        double previousVy = player.getVy();
        double moveDirection = 0;
        if (input.hasTarget) {
            double dx = input.targetX - player.getCenterX();
            if (Math.abs(dx) > GameConfig.TARGET_REACHED_TOLERANCE) {
                moveDirection = Math.signum(dx);
            } else {
                input.hasTarget = false;
            }
            player.setTargetX(input.targetX);
        }
        player.setVx(moveDirection * GameConfig.MOVE_SPEED);

        if (input.jumpQueued && player.isGrounded()) {
            player.setVy(GameConfig.JUMP_VELOCITY);
            player.setGrounded(false);
        }
        input.jumpQueued = false;

        player.setVy(player.getVy() + GameConfig.GRAVITY * dt);

        player.setX(player.getX() + player.getVx() * dt);
        resolveHorizontalCollisions(player);

        player.setY(player.getY() + player.getVy() * dt);
        resolveVerticalCollisions(player);

        clampPlayer(player);
        if (GameRules.violatesThreadDistance(player, sessionService.getPlayers().values())) {
            player.setX(previousX);
            player.setY(previousY);
            player.setVx(previousVx);
            player.setVy(Math.min(0, previousVy));
        }
    }

    private void resolveHorizontalCollisions(Player player) {
        for (PlatformTile platform : sessionService.getPlatforms()) {
            if (!GameRules.intersects(player, platform)) continue;
            if (player.getVx() > 0) {
                player.setX(platform.getX() - player.getWidth());
            } else if (player.getVx() < 0) {
                player.setX(platform.getX() + platform.getWidth());
            }
            player.setVx(0);
        }

        Door door = sessionService.getDoor();
        if (GameRules.intersects(player, door)) {
            if (player.getVx() > 0) {
                player.setX(door.getX() - player.getWidth());
            } else if (player.getVx() < 0) {
                player.setX(door.getX() + door.getWidth());
            }
            player.setVx(0);
        }
    }

    private void resolveVerticalCollisions(Player player) {
        player.setGrounded(false);
        for (PlatformTile platform : sessionService.getPlatforms()) {
            if (!GameRules.intersects(player, platform)) continue;
            if (player.getVy() > 0) {
                player.setY(platform.getY() - player.getHeight());
                player.setVy(0);
                player.setGrounded(true);
            } else if (player.getVy() < 0) {
                player.setY(platform.getY() + platform.getHeight());
                player.setVy(0);
            }
        }

        Door door = sessionService.getDoor();
        if (GameRules.intersects(player, door)) {
            if (player.getVy() > 0) {
                player.setY(door.getY() - player.getHeight());
                player.setGrounded(true);
            } else {
                player.setY(door.getY() + door.getHeight());
            }
            player.setVy(0);
        }
    }

    private void clampPlayer(Player player) {
        player.setX(Math.max(0, Math.min(GameConfig.LEVEL_WIDTH - player.getWidth(), player.getX())));
        if (player.getY() < 0) {
            player.setY(0);
            player.setVy(0);
        }
    }

    private void updateButtonAndDoor(List<Player> players) {
        ButtonSwitch button = sessionService.getButtonSwitch();
        Door door = sessionService.getDoor();
        if (button == null || door == null) return;

        boolean pressed = false;
        Player presser = null;
        for (Player player : players) {
            if (!player.isConnected()) continue;
            if (GameRules.isPressingButton(player, button)) {
                pressed = true;
                presser = player;
                break;
            }
        }

        boolean changed = button.isPressed() != pressed;
        button.setPressed(pressed);
        if (pressed) {
            door.setOpen(true);
            if (!buttonScoreAwarded && presser != null) {
                buttonScoreAwarded = true;
                awardScore(presser, GameConfig.SCORE_BUTTON_PRESS, "activo el boton");
            }
        }
        if (changed) {
            eventBus.publish(EventNames.BUTTON_STATE_CHANGED, Map.of("pressed", pressed));
        }
    }

    private void updateExitState(List<Player> players) {
        ExitZone exitZone = sessionService.getExitZone();
        for (Player player : players) {
            if (!player.isConnected()) continue;
            boolean wasAtExit = player.isAtExit();
            boolean isAtExit = GameRules.isInsideExit(player, exitZone);
            player.setAtExit(isAtExit);
            if (!wasAtExit && isAtExit) {
                player.setFinishOrder(nextFinishOrder++);
                awardScore(player, scoreForFinishOrder(player.getFinishOrder()), "llego a la salida");
                eventBus.publish(EventNames.PLAYER_REACHED_EXIT, Map.of(
                    "playerId", player.getId(),
                    "finishOrder", player.getFinishOrder()
                ));
            }
        }
    }

    private void resetRoom(String reason) {
        sessionService.setRoomResetCount(sessionService.getRoomResetCount() + 1);
        sessionService.setRoomResetReason(reason);
        resetRoomState();
        eventBus.publish(EventNames.ROOM_RESET, Map.of("reason", reason));
    }

    private void resetRoomState() {
        int index = 0;
        List<double[]> spawns = sessionService.getSpawnPoints();
        for (Player player : sessionService.getPlayers().values()) {
            if (!player.isConnected()) continue;
            double[] spawn = spawns.get(Math.min(index, spawns.size() - 1));
            player.setX(spawn[0]);
            player.setY(spawn[1]);
            player.setVx(0);
            player.setVy(0);
            player.setGrounded(false);
            player.setAlive(true);
            player.setAtExit(false);
            player.setFinishOrder(0);
            player.setTargetX(spawn[0] + player.getWidth() / 2.0);
            index++;
        }
        if (sessionService.getButtonSwitch() != null) sessionService.getButtonSwitch().setPressed(false);
        if (sessionService.getDoor() != null) sessionService.getDoor().setOpen(false);
        nextFinishOrder = 1;
    }

    private void awardScore(Player player, int delta, String reason) {
        if (delta == 0) return;
        player.addScore(delta);
        eventBus.publish(EventNames.SCORE_CHANGED, Map.of(
            "playerId", player.getId(),
            "score", player.getScore(),
            "delta", delta,
            "reason", reason
        ));
    }

    private int scoreForFinishOrder(int order) {
        return switch (order) {
            case 1 -> GameConfig.SCORE_FIRST_EXIT;
            case 2 -> GameConfig.SCORE_SECOND_EXIT;
            default -> GameConfig.SCORE_LATE_EXIT;
        };
    }

    private static final class InputState {
        double targetX;
        boolean hasTarget;
        boolean jumpQueued;
    }
}
