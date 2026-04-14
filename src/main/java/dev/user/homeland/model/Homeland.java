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
    private final AtomicInteger borderRadius;
    private final AtomicBoolean hasNether;
    private final AtomicBoolean hasEnd;
    private final AtomicBoolean isPublic;
    private VisitorFlags visitorFlags;

    public Homeland(int id, UUID ownerUuid, String name, String worldKey, int borderRadius, boolean hasNether, boolean hasEnd, boolean isPublic, VisitorFlags visitorFlags) {
        this(id, ownerUuid, name, worldKey, null, borderRadius, hasNether, hasEnd, isPublic, visitorFlags);
    }

    public Homeland(int id, UUID ownerUuid, String name, String worldKey, UUID worldUuid, int borderRadius, boolean hasNether, boolean hasEnd, boolean isPublic, VisitorFlags visitorFlags) {
        this.id = id;
        this.ownerUuid = ownerUuid;
        this.name = name;
        this.worldKey = worldKey;
        this.worldUuid = worldUuid;
        this.borderRadius = new AtomicInteger(borderRadius);
        this.hasNether = new AtomicBoolean(hasNether);
        this.hasEnd = new AtomicBoolean(hasEnd);
        this.isPublic = new AtomicBoolean(isPublic);
        this.visitorFlags = visitorFlags != null ? visitorFlags : new VisitorFlags();
    }

    public int getId() { return id; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public String getName() { return name; }
    public String getWorldKey() { return worldKey; }
    public UUID getWorldUuid() { return worldUuid; }
    public void setWorldUuid(UUID worldUuid) { this.worldUuid = worldUuid; }
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
}
