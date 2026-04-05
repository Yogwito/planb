package com.dino.application.services;

import com.dino.config.GameConfig;
import com.dino.domain.entities.ButtonSwitch;
import com.dino.domain.entities.CollectibleItem;
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
        updateCoins(players);

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
            a.setY(a.getY() + ny * pull * 0.08);
            b.setX(b.getX() - nx * pull * 0.5);
            b.setY(b.getY() - ny * pull * 0.08);

            clampPlayer(a);
            resolveHorizontalCollisions(a);
            resolveVerticalCollisions(a);
            clampPlayer(b);
            resolveHorizontalCollisions(b);
            resolveVerticalCollisions(b);

            if (distance > GameConfig.THREAD_HARD_LIMIT) {
                double excess = distance - GameConfig.THREAD_HARD_LIMIT;
                a.setX(a.getX() + nx * excess * 0.5);
                b.setX(b.getX() - nx * excess * 0.5);
                clampPlayer(a);
                resolveHorizontalCollisions(a);
                resolveVerticalCollisions(a);
                clampPlayer(b);
                resolveHorizontalCollisions(b);
                resolveVerticalCollisions(b);
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
        sessionService.getCoins().clear();

        switch (levelIndex) {
            case 0 -> loadLevelOne();
            case 1 -> loadLevelTwo();
            case 2 -> loadLevelThree();
            case 3 -> loadLevelFour();
            default -> loadLevelFive();
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
        // Tutorial: one clear 200px gap near the start, then safe 30px transitions, gentle 38px rise per step
        sessionService.getPlatforms().add(new PlatformTile("spawn_p",  80,   750, 240, 24));
        // GAP 200px (320→520) — teaches falling
        sessionService.getPlatforms().add(new PlatformTile("step_a",   520,  712, 220, 24));
        sessionService.getPlatforms().add(new PlatformTile("step_b",   770,  674, 240, 24)); // button here
        sessionService.getPlatforms().add(new PlatformTile("step_c",   1040, 636, 230, 24));
        sessionService.getPlatforms().add(new PlatformTile("door_p",   1300, 598, 220, 24));
        sessionService.getPlatforms().add(new PlatformTile("exit_p",   1550, 560, 200, 24));

        addDefaultSpawns(100, 698); // 750 - 52
        sessionService.setButtonSwitch(new ButtonSwitch("button", 850, 658, GameConfig.BUTTON_WIDTH, GameConfig.BUTTON_HEIGHT));
        sessionService.setDoor(new Door("door", 1370, 450, GameConfig.DOOR_WIDTH, GameConfig.DOOR_HEIGHT));
        sessionService.setExitZone(new ExitZone(1560, 450, GameConfig.EXIT_WIDTH, GameConfig.EXIT_HEIGHT));
        // coins: platform_y - 20  (16px coin + 4px gap above platform top)
        sessionService.getCoins().add(new CollectibleItem("l1_c1", 622, 692, GameConfig.SCORE_COIN_SMALL));  // step_a
        sessionService.getCoins().add(new CollectibleItem("l1_c2", 790, 654, GameConfig.SCORE_COIN_SMALL));  // step_b (left of button)
        sessionService.getCoins().add(new CollectibleItem("l1_c3", 1147, 616, GameConfig.SCORE_COIN_SMALL)); // step_c
        sessionService.getCoins().add(new CollectibleItem("l1_c4", 1602, 540, GameConfig.SCORE_COIN_LARGE)); // exit_p, gold
    }

    private void loadLevelTwo() {
        // Zigzag: height goes 720→760(down)→700(up)→645(up)→580(up)→640(down)→600
        // Two big gaps (GAP1=220px, GAP2=190px) + one medium gap (120px before door)
        sessionService.getPlatforms().add(new PlatformTile("spawn_p",   80,   720, 200, 24));
        // GAP1 220px (280→500)
        sessionService.getPlatforms().add(new PlatformTile("drop_a",    500,  760, 160, 24)); // drops 40px
        sessionService.getPlatforms().add(new PlatformTile("rise_b",    690,  700, 160, 24)); // rises 60px; 30px from drop_a
        // GAP2 190px (850→1040)
        sessionService.getPlatforms().add(new PlatformTile("mid_c",     1040, 645, 140, 24)); // rises 55px
        sessionService.getPlatforms().add(new PlatformTile("button_p",  1210, 580, 110, 24)); // rises 65px; 30px from mid_c
        // 120px gap (1320→1440) — requires jump, can fall
        sessionService.getPlatforms().add(new PlatformTile("door_p",    1440, 640, 180, 24)); // drops 60px
        sessionService.getPlatforms().add(new PlatformTile("exit_p",    1650, 600, 130, 24)); // rises 40px; 30px from door_p

        addDefaultSpawns(100, 668); // 720 - 52
        sessionService.setButtonSwitch(new ButtonSwitch("button", 1225, 564, GameConfig.BUTTON_WIDTH, GameConfig.BUTTON_HEIGHT));
        sessionService.setDoor(new Door("door", 1490, 492, GameConfig.DOOR_WIDTH, GameConfig.DOOR_HEIGHT));
        sessionService.setExitZone(new ExitZone(1655, 490, GameConfig.EXIT_WIDTH, GameConfig.EXIT_HEIGHT));
        sessionService.getCoins().add(new CollectibleItem("l2_c1", 572, 740, GameConfig.SCORE_COIN_SMALL));  // drop_a (y=760)
        sessionService.getCoins().add(new CollectibleItem("l2_c2", 762, 680, GameConfig.SCORE_COIN_SMALL));  // rise_b (y=700)
        sessionService.getCoins().add(new CollectibleItem("l2_c3", 1102, 625, GameConfig.SCORE_COIN_SMALL)); // mid_c  (y=645)
        sessionService.getCoins().add(new CollectibleItem("l2_c4", 1688, 580, GameConfig.SCORE_COIN_LARGE)); // exit_p (y=600), gold
    }

    private void loadLevelThree() {
        // Nivel 3 rebajado: plataformas mas anchas y saltos menos castigadores, pero sigue exigiendo coordinacion.
        sessionService.getPlatforms().add(new PlatformTile("spawn_p",   80,   710, 200, 24));
        sessionService.getPlatforms().add(new PlatformTile("stone_a",   360,  675, 150, 24));
        sessionService.getPlatforms().add(new PlatformTile("button_p",  590,  635, 180, 24));
        sessionService.getPlatforms().add(new PlatformTile("stone_b",   860,  610, 170, 24));
        sessionService.getPlatforms().add(new PlatformTile("door_p",    1100, 580, 210, 24));
        sessionService.getPlatforms().add(new PlatformTile("exit_p",    1380, 555, 230, 24));

        addDefaultSpawns(100, 658);
        sessionService.setButtonSwitch(new ButtonSwitch("button", 650, 619, GameConfig.BUTTON_WIDTH, GameConfig.BUTTON_HEIGHT));
        sessionService.setDoor(new Door("door", 1180, 432, GameConfig.DOOR_WIDTH, GameConfig.DOOR_HEIGHT));
        sessionService.setExitZone(new ExitZone(1430, 445, GameConfig.EXIT_WIDTH, GameConfig.EXIT_HEIGHT));
        sessionService.getCoins().add(new CollectibleItem("l3_c1", 420, 655, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l3_c2", 665, 615, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l3_c3", 930, 590, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l3_c4", 1190, 560, GameConfig.SCORE_COIN_LARGE));
        sessionService.getCoins().add(new CollectibleItem("l3_c5", 1500, 535, GameConfig.SCORE_COIN_SMALL));
    }

    private void loadLevelFour() {
        // Sala amplia para 4 jugadores: varias plataformas anchas donde pueden coincidir todos.
        sessionService.getPlatforms().add(new PlatformTile("spawn_p",   80,   720, 300, 24));
        sessionService.getPlatforms().add(new PlatformTile("group_a",   450,  685, 340, 24));
        sessionService.getPlatforms().add(new PlatformTile("button_p",  860,  645, 320, 24));
        sessionService.getPlatforms().add(new PlatformTile("group_b",   1240, 610, 320, 24));
        sessionService.getPlatforms().add(new PlatformTile("exit_p",    1600, 575, 180, 24));

        addDefaultSpawns(120, 668);
        sessionService.setButtonSwitch(new ButtonSwitch("button", 980, 629, GameConfig.BUTTON_WIDTH, GameConfig.BUTTON_HEIGHT));
        sessionService.setDoor(new Door("door", 1495, 462, GameConfig.DOOR_WIDTH, GameConfig.DOOR_HEIGHT));
        sessionService.setExitZone(new ExitZone(1630, 465, 150, GameConfig.EXIT_HEIGHT));
        sessionService.getCoins().add(new CollectibleItem("l4_c1", 545, 665, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l4_c2", 1005, 625, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l4_c3", 1360, 590, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l4_c4", 1675, 555, GameConfig.SCORE_COIN_LARGE));
    }

    private void loadLevelFive() {
        // Final: sigue siendo jugable en grupo, con dos grandes zonas donde los 4 caben al mismo tiempo.
        sessionService.getPlatforms().add(new PlatformTile("spawn_p",   90,   720, 320, 24));
        sessionService.getPlatforms().add(new PlatformTile("group_a",   500,  690, 360, 24));
        sessionService.getPlatforms().add(new PlatformTile("bridge",    960,  650, 240, 24));
        sessionService.getPlatforms().add(new PlatformTile("button_p",  1270, 615, 320, 24));
        sessionService.getPlatforms().add(new PlatformTile("group_b",   1500, 575, 300, 24));

        addDefaultSpawns(130, 668);
        sessionService.setButtonSwitch(new ButtonSwitch("button", 1390, 599, GameConfig.BUTTON_WIDTH, GameConfig.BUTTON_HEIGHT));
        sessionService.setDoor(new Door("door", 1685, 427, GameConfig.DOOR_WIDTH, GameConfig.DOOR_HEIGHT));
        sessionService.setExitZone(new ExitZone(1545, 465, 220, GameConfig.EXIT_HEIGHT));
        sessionService.getCoins().add(new CollectibleItem("l5_c1", 620, 670, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l5_c2", 1065, 630, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l5_c3", 1395, 595, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l5_c4", 1605, 555, GameConfig.SCORE_COIN_LARGE));
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
        for (CollectibleItem coin : sessionService.getCoins()) coin.setActive(true);
        nextFinishOrder = 1;
    }

    private void updateCoins(List<Player> players) {
        for (CollectibleItem coin : sessionService.getCoins()) {
            if (!coin.isActive()) continue;
            for (Player player : players) {
                if (!player.isConnected() || !player.isAlive()) continue;
                if (GameRules.intersects(player.getX(), player.getY(), player.getWidth(), player.getHeight(),
                        coin.getX(), coin.getY(), GameConfig.COIN_SIZE, GameConfig.COIN_SIZE)) {
                    coin.setActive(false);
                    awardScore(player, coin.getPoints(), "recogió moneda");
                    eventBus.publish(EventNames.COIN_COLLECTED, Map.of(
                        "playerId", player.getId(),
                        "coinId", coin.getId(),
                        "points", coin.getPoints(),
                        "x", coin.getX(),
                        "y", coin.getY()
                    ));
                    break;
                }
            }
        }
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
