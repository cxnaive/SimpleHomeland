package dev.user.homeland.config;

public class ExpansionPriceTier {

    private final int minRadius;
    private final double money;
    private final int points;

    public ExpansionPriceTier(int minRadius, double money, int points) {
        this.minRadius = minRadius;
        this.money = money;
        this.points = points;
    }

    public int getMinRadius() { return minRadius; }
    public double getMoney() { return money; }
    public int getPoints() { return points; }
}
