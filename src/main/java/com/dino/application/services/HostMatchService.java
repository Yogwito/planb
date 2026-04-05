package com.dino.application.services;

import com.dino.config.GameConfig;
import com.dino.domain.entities.ButtonSwitch;
import com.dino.domain.entities.CollectibleItem;
import com.dino.domain.entities.Door;
import com.dino.domain.entities.ExitZone;
import com.dino.domain.entities.PlatformTile;
import com.dino.domain.entities.Player;
import com.dino.domain.entities.PushBlock;
import com.dino.domain.events.EventNames;
import com.dino.domain.rules.GameRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulación autoritativa del host.
 *
 * <p>Recibe input de todos los jugadores, avanza la física, resuelve colisiones,
 * actualiza puntaje y decide transiciones críticas como reinicio de sala,
 * avance de nivel y fin de campaña. Es el corazón lógico de la partida: los
 * clientes no duplican esta lógica, solo la representan visualmente.</p>
 */
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

    /**
     * Construye la simulación del host con acceso al estado compartido y al bus
     * de eventos.
     */
    public HostMatchService(SessionService sessionService, EventBus eventBus) {
        this.sessionService = sessionService;
        this.eventBus = eventBus;
    }

    /**
     * Inicializa el mundo desde el primer nivel y reinicia contadores globales.
     */
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

    /**
     * Registra el último objetivo horizontal y la intención de salto de un jugador.
     *
     * @param playerId identificador del jugador
     * @param targetX objetivo horizontal en coordenadas de mundo
     * @param jumpPressed indica si el jugador solicitó salto
     */
    public void handleInput(String playerId, double targetX, boolean jumpPressed) {
        InputState state = playerInputs.computeIfAbsent(playerId, ignored -> new InputState());
        state.targetX = targetX;
        state.hasTarget = true;
        if (jumpPressed) state.jumpQueued = true;
    }

    /**
     * Avanza una iteración de simulación del host.
     *
     * <p>El orden importa: primero se actualiza movimiento individual, luego se
     * aplica el hilo, después colisiones, objetos, puntaje y por último
     * transiciones de nivel o reinicios.</p>
     *
     * @param dt tiempo transcurrido en segundos desde el tick anterior
     */
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
        updatePushBlocks(players, dt);

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

    /**
     * Actualiza movimiento, salto, gravedad y colisiones base de un jugador.
     *
     * <p>Incluye control horizontal por objetivo, coyote time y jump buffer para
     * que la sensación de control sea menos rígida.</p>
     */
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
        double acceleration;
        if (moveDirection == 0) {
            acceleration = GameConfig.MOVE_FRICTION;
        } else {
            acceleration = player.isGrounded() ? GameConfig.MOVE_ACCELERATION : GameConfig.AIR_MOVE_ACCELERATION;
        }
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

        if (input.jumpQueued) {
            input.jumpBufferTimer = GameConfig.JUMP_BUFFER_SECONDS;
        } else {
            input.jumpBufferTimer = Math.max(0, input.jumpBufferTimer - dt);
        }

        if (input.jumpBufferTimer > 0 && (player.isGrounded() || player.getCoyoteTimer() > 0)) {
            player.setVy(GameConfig.JUMP_VELOCITY);
            player.setGrounded(false);
            player.setCoyoteTimer(0);
            input.jumpBufferTimer = 0;
            eventBus.publish(EventNames.PLAYER_JUMPED, Map.of("playerId", player.getId()));
        }
        input.jumpQueued = false;

        player.setVy(player.getVy() + GameConfig.GRAVITY * dt);

        player.setX(player.getX() + player.getVx() * dt);
        resolveHorizontalCollisions(player);

        player.setY(player.getY() + player.getVy() * dt);
        resolveVerticalCollisions(player);

        if (input.jumpBufferTimer > 0 && player.isGrounded()) {
            player.setVy(GameConfig.JUMP_VELOCITY);
            player.setGrounded(false);
            player.setCoyoteTimer(0);
            input.jumpBufferTimer = 0;
            eventBus.publish(EventNames.PLAYER_JUMPED, Map.of("playerId", player.getId()));
        }

        clampPlayer(player);
        if (GameRules.violatesThreadDistance(player, sessionService.getPlayers().values())) {
            player.setX(previousX);
            player.setY(previousY);
            player.setVx(0);
            // Damp but don't zero vy: zeroing it caused "floating" jitter (gravity re-adds vy every frame)
            player.setVy(player.getVy() * 0.4);
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

        for (PushBlock block : sessionService.getPushBlocks()) {
            if (!GameRules.intersects(player, block)) continue;
            if (player.getVx() > 0) {
                player.setX(block.getX() - player.getWidth());
                block.setVx(Math.min(GameConfig.PUSH_BLOCK_MAX_SPEED,
                    Math.max(block.getVx(), player.getVx() * GameConfig.PUSH_BLOCK_PUSH_IMPULSE)));
            } else if (player.getVx() < 0) {
                player.setX(block.getX() + block.getWidth());
                block.setVx(Math.max(-GameConfig.PUSH_BLOCK_MAX_SPEED,
                    Math.min(block.getVx(), player.getVx() * GameConfig.PUSH_BLOCK_PUSH_IMPULSE)));
            }
            player.setVx(player.getVx() * 0.55);
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

        for (PushBlock block : sessionService.getPushBlocks()) {
            if (!GameRules.intersects(player, block)) continue;
            if (player.getVy() > 0) {
                player.setY(block.getY() - player.getHeight());
                player.setVy(0);
                player.setGrounded(true);
                player.setCoyoteTimer(GameConfig.COYOTE_TIME_SECONDS);
            } else if (player.getVy() < 0) {
                player.setY(block.getY() + block.getHeight());
                player.setVy(0);
            }
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
            double nx = dx / distance;
            double ny = dy / distance;
            double relativeVelocity = (b.getVx() - a.getVx()) * nx + (b.getVy() - a.getVy()) * ny;
            double springForce = stretch * GameConfig.THREAD_PULL_FACTOR;
            double dampingForce = relativeVelocity * GameConfig.THREAD_DAMPING;
            // El hilo funciona como resorte amortiguado: corrige sin teletransportar.
            double pull = Math.min(Math.max(0, (springForce - dampingForce) * dt), stretch * 0.65);

            double aMobility = a.isGrounded() ? 0.42 : 0.58;
            double bMobility = b.isGrounded() ? 0.42 : 0.58;
            double totalMobility = aMobility + bMobility;
            double aShare = totalMobility == 0 ? 0.5 : bMobility / totalMobility;
            double bShare = totalMobility == 0 ? 0.5 : aMobility / totalMobility;

            if (stretch > 8 && threadSoundCooldownRemaining <= 0) {
                threadSoundCooldownRemaining = THREAD_SOUND_COOLDOWN;
                eventBus.publish(EventNames.THREAD_STRETCHED, Map.of(
                    "playerA", a.getId(),
                    "playerB", b.getId(),
                    "stretch", stretch
                ));
            }

            a.setX(a.getX() + nx * pull * aShare);
            a.setY(a.getY() + ny * pull * GameConfig.THREAD_VERTICAL_PULL * aShare);
            b.setX(b.getX() - nx * pull * bShare);
            b.setY(b.getY() - ny * pull * GameConfig.THREAD_VERTICAL_PULL * bShare);

            a.setVx(a.getVx() + nx * pull * 0.55);
            b.setVx(b.getVx() - nx * pull * 0.55);
            if (!a.isGrounded()) a.setVy(a.getVy() + ny * pull * 0.22);
            if (!b.isGrounded()) b.setVy(b.getVy() - ny * pull * 0.22);

            clampPlayer(a);
            resolveHorizontalCollisions(a);
            resolveVerticalCollisions(a);
            clampPlayer(b);
            resolveHorizontalCollisions(b);
            resolveVerticalCollisions(b);

            if (distance > GameConfig.THREAD_HARD_LIMIT) {
                double excess = distance - GameConfig.THREAD_HARD_LIMIT;
                a.setX(a.getX() + nx * excess * aShare);
                b.setX(b.getX() - nx * excess * bShare);
                a.setVx(a.getVx() * 0.65);
                b.setVx(b.getVx() * 0.65);
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

                if (!resolveVerticalPlayerContact(a, b, overlapY) && !resolveVerticalPlayerContact(b, a, overlapY)) {
                    resolveSidePlayerContact(a, b, overlapX);
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
                resolveHorizontalCollisions(a);
                resolveVerticalCollisions(a);
                resolveHorizontalCollisions(b);
                resolveVerticalCollisions(b);
            }
        }
    }

    private boolean resolveVerticalPlayerContact(Player topCandidate, Player bottomCandidate, double overlapY) {
        double topBottom = topCandidate.getY() + topCandidate.getHeight();
        double bottomTop = bottomCandidate.getY();
        double contactGap = topBottom - bottomTop;
        boolean topIsActuallyAbove = topCandidate.getCenterY() < bottomCandidate.getCenterY();
        boolean topMovingDownIntoBottom = topCandidate.getVy() >= bottomCandidate.getVy() - 30;
        boolean shallowTopContact = contactGap > 0 && contactGap <= GameConfig.PLAYER_COLLISION_CONTACT_MARGIN;
        boolean mostlyVertical = overlapY <= Math.min(topCandidate.getHeight(), bottomCandidate.getHeight()) * 0.45;
        if (!topIsActuallyAbove || !topMovingDownIntoBottom || !(shallowTopContact || mostlyVertical)) {
            return false;
        }

        topCandidate.setY(bottomCandidate.getY() - topCandidate.getHeight() - 0.01);
        topCandidate.setVy(0);
        topCandidate.setGrounded(true);
        topCandidate.setCoyoteTimer(GameConfig.COYOTE_TIME_SECONDS);
        topCandidate.setVx(topCandidate.getVx() * (1.0 - GameConfig.PLAYER_COLLISION_CARRY_RATIO)
            + bottomCandidate.getVx() * GameConfig.PLAYER_COLLISION_CARRY_RATIO);

        if (bottomCandidate.getVy() < 0) {
            bottomCandidate.setVy(bottomCandidate.getVy() * 0.35);
        }
        return true;
    }

    private void resolveSidePlayerContact(Player a, Player b, double overlapX) {
        double push = overlapX + 0.01;
        double aMobility = a.isGrounded() ? 0.38 : 0.62;
        double bMobility = b.isGrounded() ? 0.38 : 0.62;
        double totalMobility = aMobility + bMobility;
        double aShare = totalMobility == 0 ? 0.5 : aMobility / totalMobility;
        double bShare = totalMobility == 0 ? 0.5 : bMobility / totalMobility;
        if (a.getCenterX() < b.getCenterX()) {
            a.setX(a.getX() - push * aShare);
            b.setX(b.getX() + push * bShare);
        } else {
            a.setX(a.getX() + push * aShare);
            b.setX(b.getX() - push * bShare);
        }
        a.setVx(a.getVx() * GameConfig.PLAYER_COLLISION_VELOCITY_DAMPING);
        b.setVx(b.getVx() * GameConfig.PLAYER_COLLISION_VELOCITY_DAMPING);
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

    private void updatePushBlocks(List<Player> players, double dt) {
        // Los bloques se simulan solo en el host y luego se replican por snapshot.
        for (PushBlock block : sessionService.getPushBlocks()) {
            block.setVy(block.getVy() + GameConfig.PUSH_BLOCK_GRAVITY * dt);

            double vx = block.getVx();
            if (Math.abs(vx) > 0.001) {
                double friction = GameConfig.PUSH_BLOCK_FRICTION * dt;
                if (Math.abs(vx) <= friction) {
                    vx = 0;
                } else {
                    vx -= Math.signum(vx) * friction;
                }
            }
            block.setVx(Math.max(-GameConfig.PUSH_BLOCK_MAX_SPEED, Math.min(GameConfig.PUSH_BLOCK_MAX_SPEED, vx)));

            block.setX(block.getX() + block.getVx() * dt);
            resolvePushBlockHorizontalCollisions(block, players);

            block.setY(block.getY() + block.getVy() * dt);
            resolvePushBlockVerticalCollisions(block);
            clampPushBlock(block);
        }
    }

    private void resolvePushBlockHorizontalCollisions(PushBlock block, List<Player> players) {
        for (PlatformTile platform : sessionService.getPlatforms()) {
            if (!GameRules.intersects(block, platform)) continue;
            if (block.getVx() > 0) {
                block.setX(platform.getX() - block.getWidth());
            } else if (block.getVx() < 0) {
                block.setX(platform.getX() + platform.getWidth());
            }
            block.setVx(0);
        }

        Door door = sessionService.getDoor();
        if (GameRules.intersects(block, door)) {
            if (block.getVx() > 0) {
                block.setX(door.getX() - block.getWidth());
            } else if (block.getVx() < 0) {
                block.setX(door.getX() + door.getWidth());
            }
            block.setVx(0);
        }

        for (Player player : players) {
            if (!player.isConnected() || !player.isAlive()) continue;
            if (!GameRules.intersects(player, block)) continue;
            double blockCenterX = block.getX() + block.getWidth() / 2.0;
            if (blockCenterX < player.getCenterX()) {
                player.setX(block.getX() + block.getWidth());
            } else {
                player.setX(block.getX() - player.getWidth());
            }
            player.setVx(player.getVx() * 0.5);
        }
    }

    private void resolvePushBlockVerticalCollisions(PushBlock block) {
        for (PlatformTile platform : sessionService.getPlatforms()) {
            if (!GameRules.intersects(block, platform)) continue;
            if (block.getVy() > 0) {
                block.setY(platform.getY() - block.getHeight());
            } else if (block.getVy() < 0) {
                block.setY(platform.getY() + platform.getHeight());
            }
            block.setVy(0);
        }

        Door door = sessionService.getDoor();
        if (GameRules.intersects(block, door)) {
            if (block.getVy() > 0) {
                block.setY(door.getY() - block.getHeight());
            } else if (block.getVy() < 0) {
                block.setY(door.getY() + door.getHeight());
            }
            block.setVy(0);
        }
    }

    private void clampPushBlock(PushBlock block) {
        block.setX(Math.max(0, Math.min(GameConfig.LEVEL_WIDTH - block.getWidth(), block.getX())));
        if (block.getY() < 0) {
            block.setY(0);
            block.setVy(0);
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

    /**
     * Reinicia la sala actual completa para todos los jugadores conectados.
     *
     * @param reason texto corto para UI y debug
     */
    private void resetRoom(String reason) {
        sessionService.setRoomResetCount(sessionService.getRoomResetCount() + 1);
        sessionService.setRoomResetReason(reason);
        resetRoomState();
        eventBus.publish(EventNames.ROOM_RESET, Map.of("reason", reason));
    }

    /**
     * Avanza al siguiente nivel o finaliza la campaña si era el último.
     */
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

    /**
     * Carga la geometría y objetos de una sala concreta.
     *
     * @param levelIndex índice del nivel a cargar
     * @param resetScores si {@code true}, también reinicia puntajes y caídas
     */
    private void loadLevel(int levelIndex, boolean resetScores) {
        sessionService.setCurrentLevelIndex(levelIndex);
        sessionService.getPlatforms().clear();
        sessionService.getSpawnPoints().clear();
        sessionService.getPushBlocks().clear();
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
        sessionService.getPushBlocks().add(new PushBlock("l1_box", 330, 682, GameConfig.PUSH_BLOCK_WIDTH, GameConfig.PUSH_BLOCK_HEIGHT));
        // coins: platform_y - 20  (16px coin + 4px gap above platform top)
        sessionService.getCoins().add(new CollectibleItem("l1_c1", 622, 692, GameConfig.SCORE_COIN_SMALL));  // step_a
        sessionService.getCoins().add(new CollectibleItem("l1_c2", 790, 654, GameConfig.SCORE_COIN_SMALL));  // step_b (left of button)
        sessionService.getCoins().add(new CollectibleItem("l1_c3", 1147, 616, GameConfig.SCORE_COIN_SMALL)); // step_c
        sessionService.getCoins().add(new CollectibleItem("l1_c4", 1602, 540, GameConfig.SCORE_COIN_LARGE)); // exit_p, gold
    }

    private void loadLevelTwo() {
        // Zigzag mas amable: huecos mas cortos y plataformas de recepcion mas anchas.
        sessionService.getPlatforms().add(new PlatformTile("spawn_p",   80,   720, 220, 24));
        sessionService.getPlatforms().add(new PlatformTile("drop_a",    470,  748, 190, 24));
        sessionService.getPlatforms().add(new PlatformTile("rise_b",    680,  698, 180, 24));
        sessionService.getPlatforms().add(new PlatformTile("mid_c",     990,  648, 180, 24));
        sessionService.getPlatforms().add(new PlatformTile("button_p",  1190, 592, 145, 24));
        sessionService.getPlatforms().add(new PlatformTile("door_p",    1400, 632, 210, 24));
        sessionService.getPlatforms().add(new PlatformTile("exit_p",    1630, 600, 160, 24));

        addDefaultSpawns(100, 668); // 720 - 52
        sessionService.setButtonSwitch(new ButtonSwitch("button", 1230, 576, GameConfig.BUTTON_WIDTH, GameConfig.BUTTON_HEIGHT));
        sessionService.setDoor(new Door("door", 1470, 484, GameConfig.DOOR_WIDTH, GameConfig.DOOR_HEIGHT));
        sessionService.setExitZone(new ExitZone(1650, 490, GameConfig.EXIT_WIDTH, GameConfig.EXIT_HEIGHT));
        sessionService.getCoins().add(new CollectibleItem("l2_c1", 548, 728, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l2_c2", 760, 678, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l2_c3", 1080, 626, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l2_c4", 1698, 580, GameConfig.SCORE_COIN_LARGE));
    }

    private void loadLevelThree() {
        // Nivel 3 un poco mas amable: mas continuidad horizontal y recepciones mas amplias.
        sessionService.getPlatforms().add(new PlatformTile("spawn_p",   80,   710, 220, 24));
        sessionService.getPlatforms().add(new PlatformTile("stone_a",   340,  680, 185, 24));
        sessionService.getPlatforms().add(new PlatformTile("button_p",  575,  638, 210, 24));
        sessionService.getPlatforms().add(new PlatformTile("stone_b",   835,  614, 200, 24));
        sessionService.getPlatforms().add(new PlatformTile("door_p",    1085, 584, 235, 24));
        sessionService.getPlatforms().add(new PlatformTile("exit_p",    1360, 560, 255, 24));

        addDefaultSpawns(100, 658);
        sessionService.setButtonSwitch(new ButtonSwitch("button", 652, 622, GameConfig.BUTTON_WIDTH, GameConfig.BUTTON_HEIGHT));
        sessionService.setDoor(new Door("door", 1170, 436, GameConfig.DOOR_WIDTH, GameConfig.DOOR_HEIGHT));
        sessionService.setExitZone(new ExitZone(1435, 450, GameConfig.EXIT_WIDTH, GameConfig.EXIT_HEIGHT));
        sessionService.getPushBlocks().add(new PushBlock("l3_box", 995, 546, GameConfig.PUSH_BLOCK_WIDTH, GameConfig.PUSH_BLOCK_HEIGHT));
        sessionService.getCoins().add(new CollectibleItem("l3_c1", 425, 660, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l3_c2", 668, 618, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l3_c3", 935, 594, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l3_c4", 1195, 564, GameConfig.SCORE_COIN_LARGE));
        sessionService.getCoins().add(new CollectibleItem("l3_c5", 1508, 540, GameConfig.SCORE_COIN_SMALL));
    }

    private void loadLevelFour() {
        // Sala amplia para 4 jugadores: mismas ideas pero con mas espacio de reunion y menos hueco final.
        sessionService.getPlatforms().add(new PlatformTile("spawn_p",   80,   720, 320, 24));
        sessionService.getPlatforms().add(new PlatformTile("group_a",   430,  688, 370, 24));
        sessionService.getPlatforms().add(new PlatformTile("button_p",  845,  648, 340, 24));
        sessionService.getPlatforms().add(new PlatformTile("group_b",   1230, 615, 345, 24));
        sessionService.getPlatforms().add(new PlatformTile("exit_p",    1580, 582, 220, 24));

        addDefaultSpawns(120, 668);
        sessionService.setButtonSwitch(new ButtonSwitch("button", 985, 632, GameConfig.BUTTON_WIDTH, GameConfig.BUTTON_HEIGHT));
        sessionService.setDoor(new Door("door", 1480, 468, GameConfig.DOOR_WIDTH, GameConfig.DOOR_HEIGHT));
        sessionService.setExitZone(new ExitZone(1620, 470, 165, GameConfig.EXIT_HEIGHT));
        sessionService.getCoins().add(new CollectibleItem("l4_c1", 555, 668, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l4_c2", 1010, 628, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l4_c3", 1375, 595, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l4_c4", 1685, 562, GameConfig.SCORE_COIN_LARGE));
    }

    private void loadLevelFive() {
        // Final ligeramente suavizado: puente mas ancho y zonas finales mas seguras.
        sessionService.getPlatforms().add(new PlatformTile("spawn_p",   90,   720, 340, 24));
        sessionService.getPlatforms().add(new PlatformTile("group_a",   485,  692, 380, 24));
        sessionService.getPlatforms().add(new PlatformTile("bridge",    940,  652, 285, 24));
        sessionService.getPlatforms().add(new PlatformTile("button_p",  1250, 618, 340, 24));
        sessionService.getPlatforms().add(new PlatformTile("group_b",   1485, 582, 330, 24));

        addDefaultSpawns(130, 668);
        sessionService.setButtonSwitch(new ButtonSwitch("button", 1395, 602, GameConfig.BUTTON_WIDTH, GameConfig.BUTTON_HEIGHT));
        sessionService.setDoor(new Door("door", 1675, 435, GameConfig.DOOR_WIDTH, GameConfig.DOOR_HEIGHT));
        sessionService.setExitZone(new ExitZone(1545, 470, 235, GameConfig.EXIT_HEIGHT));
        sessionService.getCoins().add(new CollectibleItem("l5_c1", 630, 672, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l5_c2", 1080, 632, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l5_c3", 1405, 598, GameConfig.SCORE_COIN_SMALL));
        sessionService.getCoins().add(new CollectibleItem("l5_c4", 1615, 562, GameConfig.SCORE_COIN_LARGE));
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
        for (PushBlock block : sessionService.getPushBlocks()) {
            block.setX(block.getHomeX());
            block.setY(block.getHomeY());
            block.setVx(0);
            block.setVy(0);
        }
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
        double jumpBufferTimer;
    }
}
