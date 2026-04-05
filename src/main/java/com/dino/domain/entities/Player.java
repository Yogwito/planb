package com.dino.domain.entities;

public class Player {
    private String id;
    private String name;
    private String color;
    private double x;
    private double y;
    private int score;
    private boolean connected;
    private double penaltyUntil;

    public Player() {}

    public Player(String id, String name, String color) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.connected = true;
    }

    public void applyPenalty(double duration, int points, double now) {
        this.penaltyUntil = now + duration;
        this.score += points;
    }

    public boolean isPenalized(double now) {
        return now < penaltyUntil;
    }

    public double effectiveSpeed(double baseSpeed, double now) {
        return isPenalized(now) ? baseSpeed * 0.4 : baseSpeed;
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
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }
    public double getPenaltyUntil() { return penaltyUntil; }
    public void setPenaltyUntil(double penaltyUntil) { this.penaltyUntil = penaltyUntil; }
}
