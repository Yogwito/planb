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
    private static final double COLLISION_SOUND_COOLDOWN = 0.12;
    private static final double THREAD_SOUND_COOLDOWN = 0.16;

    private final SessionService sessionService;
    private final EventBus eventBus;
    private final Map<String, InputState> playerInputs = new HashMap<>();
    private boolean gameOver = false;
    private boolean buttonScoreAwarded = false;
    private int nextFinishOrder = 1;
    private double collisionSoundCooldownRemaining = 0;
    private double threadSoundCooldownRemaining = 0;

    public HostMatchService(SessionService sessionService, EventBus eventBus) {
        this.sessionService = sessionService;
        this.eventBus = eventBus;
    }

    public void initWorld() {
        sessionService.setCurrentLevelIndex(0);
        sessionService.setTotalLevels(GameConfig.TOTAL_LEVELS);
        sessionService.setElapsedTime(0);
        sessionService.setGameRunning(true);
        sessionService.setRoomResetCount(0);
        sessionService.setRoomResetReason("");
        playerInputs.clear();
        gameOver = false;
        buttonScoreAwarded = false;
        nextFinishOrder = 1;
        loadLevel(0, true);
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
        collisionSoundCooldownRemaining = Math.max(0, collisionSoundCooldownRemaining - dt);
        threadSoundCooldownRemaining = Math.max(0, threadSoundCooldownRemaining - dt);

        List<Player> players = new ArrayList<>(sessionService.getPlayers().values());
        for (Player player : players) {
            if (!player.isConnected()) continue;
            updatePlayer(player, dt);
        }

        applyThreadElasticity(players, dt);
        resolvePlayerCollisions(players);

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
            advanceLevelOrFinish();
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
        double targetVelocity = moveDirection * GameConfig.MOVE_SPEED;
        double velocityDelta = targetVelocity - player.getVx();
        double acceleration = moveDirection == 0 ? GameConfig.MOVE_FRICTION : GameConfig.MOVE_ACCELERATION;
        double maxDelta = acceleration * dt;
        if (Math.abs(velocityDelta) > maxDelta) {
            velocityDelta = Math.signum(velocityDelta) * maxDelta;
        }
        player.setVx(player.getVx() + velocityDelta);

        if (player.isGrounded()) {
            player.setCoyoteTimer(GameConfig.COYOTE_TIME_SECONDS);
        } else {
            player.setCoyoteTimer(Math.max(0, player.getCoyoteTimer() - dt));
        }

        if (input.jumpQueued && (player.isGrounded() || player.getCoyoteTimer() > 0)) {
            player.setVy(GameConfig.JUMP_VELOCITY);
            player.setGrounded(false);
            player.setCoyoteTimer(0);
            eventBus.publish(EventNames.PLAYER_JUMPED, Map.of("playerId", player.getId()));
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
                player.setCoyoteTimer(GameConfig.COYOTE_TIME_SECONDS);
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
                player.setCoyoteTimer(GameConfig.COYOTE_TIME_SECONDS);
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

    private void applyThreadElasticity(List<Player> players, double dt) {
        List<Player> connected = players.stream()
            .filter(player -> player.isConnected() && player.isAlive())
            .sorted((a, b) -> Double.compare(a.getX(), b.getX()))
            .toList();

        for (int i = 0; i < connected.size() - 1; i++) {
            Player a = connected.get(i);
            Player b = connected.get(i + 1);
            double dx = b.getCenterX() - a.getCenterX();
            double dy = b.getCenterY() - a.getCenterY();
            double distance = Math.sqrt(dx * dx + dy * dy);
            if (distance <= GameConfig.THREAD_REST_DISTANCE || distance == 0) continue;

            double stretch = distance - GameConfig.THREAD_REST_DISTANCE;
            double pull = Math.min(stretch * GameConfig.THREAD_PULL_FACTOR * dt, stretch * 0.5);
            double nx = dx / distance;
            double ny = dy / distance;

            if (stretch > 8 && threadSoundCooldownRemaining <= 0) {
                threadSoundCooldownRemaining = THREAD_SOUND_COOLDOWN;
                eventBus.publish(EventNames.THREAD_STRETCHED, Map.of(
                    "playerA", a.getId(),
                    "playerB", b.getId(),
                    "stretch", stretch
                ));
            }

            a.setX(a.getX() + nx * pull * 0.5);
            a.setY(a.getY() + ny * pull * 0.18);
            b.setX(b.getX() - nx * pull * 0.5);
            b.setY(b.getY() - ny * pull * 0.18);

            clampPlayer(a);
            clampPlayer(b);

            if (distance > GameConfig.THREAD_HARD_LIMIT) {
                double excess = distance - GameConfig.THREAD_HARD_LIMIT;
                a.setX(a.getX() + nx * excess * 0.5);
                b.setX(b.getX() - nx * excess * 0.5);
                clampPlayer(a);
                clampPlayer(b);
            }
        }
    }

    private void resolvePlayerCollisions(List<Player> players) {
        List<Player> connected = players.stream()
            .filter(player -> player.isConnected() && player.isAlive())
            .toList();

        for (int i = 0; i < connected.size(); i++) {
            for (int j = i + 1; j < connected.size(); j++) {
                Player a = connected.get(i);
                Player b = connected.get(j);
                if (!GameRules.intersects(a, b)) continue;

                double overlapX = Math.min(a.getX() + a.getWidth(), b.getX() + b.getWidth()) - Math.max(a.getX(), b.getX());
                double overlapY = Math.min(a.getY() + a.getHeight(), b.getY() + b.getHeight()) - Math.max(a.getY(), b.getY());
                if (overlapX <= 0 || overlapY <= 0) continue;

                if (overlapX < overlapY) {
                    double push = overlapX / 2.0 + 0.01;
                    if (a.getCenterX() < b.getCenterX()) {
                        a.setX(a.getX() - push);
                        b.setX(b.getX() + push);
                    } else {
                        a.setX(a.getX() + push);
                        b.setX(b.getX() - push);
                    }
                    a.setVx(0);
                    b.setVx(0);
                } else {
                    double push = overlapY / 2.0 + 0.01;
                    if (a.getCenterY() < b.getCenterY()) {
                        a.setY(a.getY() - push);
                        b.setY(b.getY() + push);
                        a.setGrounded(true);
                    } else {
                        a.setY(a.getY() + push);
                        b.setY(b.getY() - push);
                        b.setGrounded(true);
                    }
                    a.setVy(0);
                    b.setVy(0);
                }

                if (collisionSoundCooldownRemaining <= 0) {
                    collisionSoundCooldownRemaining = COLLISION_SOUND_COOLDOWN;
                    eventBus.publish(EventNames.PLAYER_COLLIDED, Map.of(
                        "playerA", a.getId(),
                        "playerB", b.getId()
                    ));
                }

                clampPlayer(a);
                clampPlayer(b);
            }
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

    private void advanceLevelOrFinish() {
        int nextLevel = sessionService.getCurrentLevelIndex() + 1;
        if (nextLevel >= sessionService.getTotalLevels()) {
            gameOver = true;
            sessionService.setGameRunning(false);
            eventBus.publish(EventNames.LEVEL_COMPLETED, Map.of(
                "elapsedTime", sessionService.getElapsedTime(),
                "levelIndex", sessionService.getCurrentLevelIndex()
            ));
            eventBus.publish(EventNames.GAME_OVER, Map.of("elapsedTime", sessionService.getElapsedTime()));
            return;
        }

        loadLevel(nextLevel, false);
        eventBus.publish(EventNames.LEVEL_ADVANCED, Map.of("levelIndex", nextLevel));
    }

    private void loadLevel(int levelIndex, boolean resetScores) {
        sessionService.setCurrentLevelIndex(levelIndex);
        sessionService.getPlatforms().clear();
        sessionService.getSpawnPoints().clear();

        sessionService.getPlatforms().add(new PlatformTile("ground", 0, 820, GameConfig.LEVEL_WIDTH, 80));
        switch (levelIndex) {
            case 0 -> loadLevelOne();
            case 1 -> loadLevelTwo();
            default -> loadLevelThree();
        }

        if (resetScores) {
            for (Player player : sessionService.getPlayers().values()) {
                player.setScore(0);
                player.setDeaths(0);
            }
        }
        buttonScoreAwarded = false;
        resetRoomState();
    }

    private void loadLevelOne() {
        sessionService.getPlatforms().add(new PlatformTile("spawn_step", 150, 730, 220, 24));
        sessionService.getPlatforms().add(new PlatformTile("button_ledge", 430, 690, 220, 24));
        sessionService.getPlatforms().add(new PlatformTile("bridge_step", 710, 650, 200, 24));
        sessionService.getPlatforms().add(new PlatformTile("door_ledge", 960, 610, 220, 24));
        sessionService.getPlatforms().add(new PlatformTile("exit_ledge", 1220, 570, 220, 24));

        addDefaultSpawns(120, 678);
        sessionService.setButtonSwitch(new ButtonSwitch("button", 500, 674, GameConfig.BUTTON_WIDTH, GameConfig.BUTTON_HEIGHT));
        sessionService.setDoor(new Door("door", 1042, 462, GameConfig.DOOR_WIDTH, GameConfig.DOOR_HEIGHT));
        sessionService.setExitZone(new ExitZone(1265, 460, GameConfig.EXIT_WIDTH, GameConfig.EXIT_HEIGHT));
    }

    private void loadLevelTwo() {
        sessionService.getPlatforms().add(new PlatformTile("spawn_step", 140, 730, 190, 24));
        sessionService.getPlatforms().add(new PlatformTile("mid_one", 360, 690, 170, 24));
        sessionService.getPlatforms().add(new PlatformTile("button_ledge", 590, 650, 180, 24));
        sessionService.getPlatforms().add(new PlatformTile("mid_two", 850, 610, 180, 24));
        sessionService.getPlatforms().add(new PlatformTile("door_ledge", 1120, 570, 220, 24));
        sessionService.getPlatforms().add(new PlatformTile("mid_three", 1380, 530, 170, 24));
        sessionService.getPlatforms().add(new PlatformTile("exit_ledge", 1570, 490, 190, 24));

        addDefaultSpawns(110, 678);
        sessionService.setButtonSwitch(new ButtonSwitch("button", 645, 634, GameConfig.BUTTON_WIDTH, GameConfig.BUTTON_HEIGHT));
        sessionService.setDoor(new Door("door", 1212, 422, GameConfig.DOOR_WIDTH, GameConfig.DOOR_HEIGHT));
        sessionService.setExitZone(new ExitZone(1605, 380, GameConfig.EXIT_WIDTH, GameConfig.EXIT_HEIGHT));
    }

    private void loadLevelThree() {
        sessionService.getPlatforms().add(new PlatformTile("spawn_step", 140, 730, 180, 24));
        sessionService.getPlatforms().add(new PlatformTile("mid_one", 340, 700, 170, 24));
        sessionService.getPlatforms().add(new PlatformTile("button_ledge", 560, 665, 190, 24));
        sessionService.getPlatforms().add(new PlatformTile("mid_two", 800, 625, 180, 24));
        sessionService.getPlatforms().add(new PlatformTile("mid_three", 1010, 585, 170, 24));
        sessionService.getPlatforms().add(new PlatformTile("door_ledge", 1235, 545, 190, 24));
        sessionService.getPlatforms().add(new PlatformTile("mid_four", 1470, 505, 170, 24));
        sessionService.getPlatforms().add(new PlatformTile("exit_ledge", 1660, 465, 170, 24));

        addDefaultSpawns(120, 678);
        sessionService.setButtonSwitch(new ButtonSwitch("button", 615, 649, GameConfig.BUTTON_WIDTH, GameConfig.BUTTON_HEIGHT));
        sessionService.setDoor(new Door("door", 1312, 397, GameConfig.DOOR_WIDTH, GameConfig.DOOR_HEIGHT));
        sessionService.setExitZone(new ExitZone(1695, 355, GameConfig.EXIT_WIDTH, GameConfig.EXIT_HEIGHT));
    }

    private void addDefaultSpawns(double baseX, double baseY) {
        sessionService.getSpawnPoints().add(new double[]{baseX, baseY});
        sessionService.getSpawnPoints().add(new double[]{baseX + 60, baseY});
        sessionService.getSpawnPoints().add(new double[]{baseX + 120, baseY});
        sessionService.getSpawnPoints().add(new double[]{baseX + 180, baseY});
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
            player.setCoyoteTimer(0);
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
