package com.dino.domain.entities;

import com.dino.config.GameConfig;

public class Player {
    private String id;
    private String name;
    private String color;
    private double x;
    private double y;
    private double vx;
    private double vy;
    private boolean grounded;
    private boolean alive;
    private boolean atExit;
    private double targetX;
    private int score;
    private int deaths;
    private int finishOrder;
    private boolean connected;
    private boolean ready;

    public Player() {}

    public Player(String id, String name, String color) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.alive = true;
        this.connected = true;
        this.ready = false;
        this.targetX = 0;
    }

    public double getWidth() {
        return GameConfig.PLAYER_WIDTH;
    }

    public double getHeight() {
        return GameConfig.PLAYER_HEIGHT;
    }

    public double getCenterX() {
        return x + getWidth() / 2.0;
    }

    public double getCenterY() {
        return y + getHeight() / 2.0;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public void addScore(int delta) {
        this.score = Math.max(0, this.score + delta);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public double getX() { return x; }
    public void setX(double x) { this.x = x; }
    public double getY() { return y; }
    public void setY(double y) { this.y = y; }
    public double getVx() { return vx; }
    public void setVx(double vx) { this.vx = vx; }
    public double getVy() { return vy; }
    public void setVy(double vy) { this.vy = vy; }
    public boolean isGrounded() { return grounded; }
    public void setGrounded(boolean grounded) { this.grounded = grounded; }
    public boolean isAlive() { return alive; }
    public void setAlive(boolean alive) { this.alive = alive; }
    public boolean isAtExit() { return atExit; }
    public void setAtExit(boolean atExit) { this.atExit = atExit; }
    public double getTargetX() { return targetX; }
    public void setTargetX(double targetX) { this.targetX = targetX; }
    public int getDeaths() { return deaths; }
    public void setDeaths(int deaths) { this.deaths = deaths; }
    public int getFinishOrder() { return finishOrder; }
    public void setFinishOrder(int finishOrder) { this.finishOrder = finishOrder; }
    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }
    public boolean isReady() { return ready; }
    public void setReady(boolean ready) { this.ready = ready; }
}
