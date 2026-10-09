package dev.user.homeland.compat;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Uses the same native asynchronous portal operation as Worlds 4 on Folia 26.2. */
public final class FoliaPortalBridge {
    private final Constructor<?> blockPosition;
    private final Method portalToAsync;
    private final Object nether;
    private final Object end;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public FoliaPortalBridge() throws ReflectiveOperationException {
        Class<?> entityClass = Class.forName("net.minecraft.world.entity.Entity");
        Class<?> positionClass = Class.forName("net.minecraft.core.BlockPos");
        Class<?> levelClass = Class.forName("net.minecraft.server.level.ServerLevel");
        Class<? extends Enum> portalType = (Class<? extends Enum>) Class.forName(
                "net.minecraft.world.entity.Entity$PortalType");
        nether = Enum.valueOf(portalType, "NETHER");
        end = Enum.valueOf(portalType, "END");
        blockPosition = positionClass.getConstructor(int.class, int.class, int.class);
        portalToAsync = entityClass.getDeclaredMethod("portalToAsync", levelClass,
                positionClass, boolean.class, portalType, Consumer.class);
        portalToAsync.setAccessible(true);
    }

    public boolean teleport(Entity entity, World target, Location portal, boolean netherPortal)
            throws ReflectiveOperationException {
        Object entityHandle = entity.getClass().getMethod("getHandle").invoke(entity);
        Object levelHandle = target.getClass().getMethod("getHandle").invoke(target);
        Object position = blockPosition.newInstance(portal.getBlockX(), portal.getBlockY(), portal.getBlockZ());
        return Boolean.TRUE.equals(portalToAsync.invoke(entityHandle, levelHandle,
                position, true, netherPortal ? nether : end, null));
    }
}
