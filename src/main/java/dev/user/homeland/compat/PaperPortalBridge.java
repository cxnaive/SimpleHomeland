package dev.user.homeland.compat;

import org.bukkit.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Reads Paper's per-world credits setting without depending on its server internals at compile time. */
public final class PaperPortalBridge {
    private final Method worldHandle;
    private final Method paperConfig;
    private final Field misc;
    private final Field disableCredits;

    public PaperPortalBridge() throws ReflectiveOperationException {
        worldHandle = Class.forName("org.bukkit.craftbukkit.CraftWorld").getMethod("getHandle");
        paperConfig = worldHandle.getReturnType().getMethod("paperConfig");
        misc = paperConfig.getReturnType().getField("misc");
        disableCredits = misc.getType().getField("disableEndCredits");
    }

    public boolean creditsEnabled(World source) throws ReflectiveOperationException {
        Object config = paperConfig.invoke(worldHandle.invoke(source));
        return !disableCredits.getBoolean(misc.get(config));
    }
}
