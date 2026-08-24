package dev.user.homeland.model;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class Homeland {

    private final int id;
    private final UUID ownerUuid;
    private final String name;
    private final String worldKey;
    private volatile UUID worldUuid;
    private volatile String worldType;
    private final AtomicBoolean overworldResetPending;
    private volatile UUID overworldResetPreviousUuid;
    private final AtomicInteger borderRadius;
    private final AtomicBoolean hasNether;
    private final AtomicBoolean hasEnd;
    private final AtomicBoolean isPublic;
    private volatile VisitorFlags visitorFlags;
    private volatile CustomSettings customSettings;

    public Homeland(int id, UUID ownerUuid, String name, String worldKey, int borderRadius, boolean hasNether, boolean hasEnd, boolean isPublic, VisitorFlags visitorFlags) {
        this(id, ownerUuid, name, worldKey, null, "default", borderRadius, hasNether, hasEnd, isPublic, visitorFlags, new CustomSettings(), false, null);
    }

    public Homeland(int id, UUID ownerUuid, String name, String worldKey, UUID worldUuid, int borderRadius, boolean hasNether, boolean hasEnd, boolean isPublic, VisitorFlags visitorFlags) {
        this(id, ownerUuid, name, worldKey, worldUuid, "default", borderRadius, hasNether, hasEnd, isPublic, visitorFlags, new CustomSettings(), false, null);
    }

    public Homeland(int id, UUID ownerUuid, String name, String worldKey, UUID worldUuid, String worldType, int borderRadius, boolean hasNether, boolean hasEnd, boolean isPublic, VisitorFlags visitorFlags) {
        this(id, ownerUuid, name, worldKey, worldUuid, worldType, borderRadius, hasNether, hasEnd, isPublic, visitorFlags, new CustomSettings(), false, null);
    }

    public Homeland(int id, UUID ownerUuid, String name, String worldKey, UUID worldUuid, String worldType, int borderRadius, boolean hasNether, boolean hasEnd, boolean isPublic, VisitorFlags visitorFlags, CustomSettings customSettings) {
        this(id, ownerUuid, name, worldKey, worldUuid, worldType, borderRadius,
                hasNether, hasEnd, isPublic, visitorFlags, customSettings, false);
    }

    public Homeland(int id, UUID ownerUuid, String name, String worldKey, UUID worldUuid,
                    String worldType, int borderRadius, boolean hasNether, boolean hasEnd,
                    boolean isPublic, VisitorFlags visitorFlags, CustomSettings customSettings,
                    boolean overworldResetPending) {
        this(id, ownerUuid, name, worldKey, worldUuid, worldType, borderRadius,
                hasNether, hasEnd, isPublic, visitorFlags, customSettings,
                overworldResetPending, null);
    }

    public Homeland(int id, UUID ownerUuid, String name, String worldKey, UUID worldUuid,
                    String worldType, int borderRadius, boolean hasNether, boolean hasEnd,
                    boolean isPublic, VisitorFlags visitorFlags, CustomSettings customSettings,
                    boolean overworldResetPending, UUID overworldResetPreviousUuid) {
        this.id = id;
        this.ownerUuid = ownerUuid;
        this.name = name;
        this.worldKey = worldKey;
        this.worldUuid = worldUuid;
        this.worldType = worldType != null ? worldType : "default";
        this.overworldResetPending = new AtomicBoolean(overworldResetPending);
        this.overworldResetPreviousUuid = overworldResetPreviousUuid;
        this.borderRadius = new AtomicInteger(borderRadius);
        this.hasNether = new AtomicBoolean(hasNether);
        this.hasEnd = new AtomicBoolean(hasEnd);
        this.isPublic = new AtomicBoolean(isPublic);
        this.visitorFlags = visitorFlags != null ? visitorFlags : new VisitorFlags();
        this.customSettings = customSettings != null ? customSettings : new CustomSettings();
    }

    public int getId() { return id; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public String getName() { return name; }
    public String getWorldKey() { return worldKey; }
    public UUID getWorldUuid() { return worldUuid; }
    public void setWorldUuid(UUID worldUuid) { this.worldUuid = worldUuid; }
    public String getWorldType() { return worldType; }
    public void setWorldType(String worldType) { this.worldType = worldType != null ? worldType : "default"; }
    public boolean isOverworldResetPending() { return overworldResetPending.get(); }
    public void setOverworldResetPending(boolean pending) { overworldResetPending.set(pending); }
    public UUID getOverworldResetPreviousUuid() { return overworldResetPreviousUuid; }
    public void setOverworldResetPreviousUuid(UUID worldUuid) { this.overworldResetPreviousUuid = worldUuid; }
    public int getBorderRadius() { return borderRadius.get(); }
    public boolean hasNether() { return hasNether.get(); }
    public boolean hasEnd() { return hasEnd.get(); }
    public boolean isPublic() { return isPublic.get(); }

    public void setBorderRadius(int borderRadius) { this.borderRadius.set(borderRadius); }
    public void setHasNether(boolean hasNether) { this.hasNether.set(hasNether); }
    public void setHasEnd(boolean hasEnd) { this.hasEnd.set(hasEnd); }
    public void setPublic(boolean isPublic) { this.isPublic.set(isPublic); }
    public VisitorFlags getVisitorFlags() { return visitorFlags; }
    public void setVisitorFlags(VisitorFlags visitorFlags) { this.visitorFlags = visitorFlags; }
    public CustomSettings getCustomSettings() { return customSettings; }
    public void setCustomSettings(CustomSettings customSettings) { this.customSettings = customSettings; }
}
