package com.dino.domain.entities;

public class PenaltyZone {
    private String id;
    private double x;
    private double y;
    private double radius;
    private double triggerMass;
    private double burstRatio;

    public PenaltyZone() {}

    public PenaltyZone(String id, double x, double y, double radius, double triggerMass, double burstRatio) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.radius = radius;
        this.triggerMass = triggerMass;
        this.burstRatio = burstRatio;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public double getX() { return x; }
    public void setX(double x) { this.x = x; }
    public double getY() { return y; }
    public void setY(double y) { this.y = y; }
    public double getRadius() { return radius; }
    public void setRadius(double radius) { this.radius = radius; }
    public double getTriggerMass() { return triggerMass; }
    public void setTriggerMass(double triggerMass) { this.triggerMass = triggerMass; }
    public double getBurstRatio() { return burstRatio; }
    public void setBurstRatio(double burstRatio) { this.burstRatio = burstRatio; }
}
