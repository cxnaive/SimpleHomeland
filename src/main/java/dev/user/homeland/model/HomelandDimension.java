package dev.user.homeland.model;

import java.util.Locale;

public enum HomelandDimension {
    OVERWORLD("", "主世界"),
    NETHER("_nether", "地狱"),
    END("_the_end", "末地");

    private final String worldKeySuffix;
    private final String displayName;

    HomelandDimension(String worldKeySuffix, String displayName) {
        this.worldKeySuffix = worldKeySuffix;
        this.displayName = displayName;
    }

    public String worldKey(Homeland homeland) {
        return homeland.getWorldKey() + worldKeySuffix;
    }

    public String worldKeySuffix() {
        return worldKeySuffix;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isUnlocked(Homeland homeland) {
        return switch (this) {
            case OVERWORLD -> true;
            case NETHER -> homeland.hasNether();
            case END -> homeland.hasEnd();
        };
    }

    public void setUnlocked(Homeland homeland, boolean unlocked) {
        switch (this) {
            case OVERWORLD -> throw new IllegalStateException("Overworld cannot be locked");
            case NETHER -> homeland.setHasNether(unlocked);
            case END -> homeland.setHasEnd(unlocked);
        }
    }

    public String databaseColumn() {
        return switch (this) {
            case OVERWORLD -> throw new IllegalStateException("Overworld has no unlock column");
            case NETHER -> "has_nether";
            case END -> "has_end";
        };
    }

    public static HomelandDimension parse(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "overworld" -> OVERWORLD;
            case "nether" -> NETHER;
            case "end" -> END;
            default -> throw new IllegalArgumentException("Unknown homeland dimension: " + value);
        };
    }
}
