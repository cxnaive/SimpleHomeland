package dev.user.homeland.listener;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.compat.FoliaPortalBridge;
import dev.user.homeland.manager.HomelandWorldKeys;
import dev.user.homeland.model.Homeland;
import dev.user.homeland.model.HomelandDimension;
import dev.user.homeland.util.MessageUtil;
import io.papermc.paper.event.entity.EntityPortalReadyEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps portals within one homeland despite Worlds 4 grouping portals by namespace. */
public final class HomelandPortalListener implements Listener {
    private final SimpleHomelandPlugin plugin;
    private final FoliaPortalBridge folia;
    private final Map<UUID, PortalTicks> ticks = new ConcurrentHashMap<>();
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
    private final Set<UUID> messages = ConcurrentHashMap.newKeySet();

    public HomelandPortalListener(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
        FoliaPortalBridge bridge = null;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            bridge = new FoliaPortalBridge();
        } catch (ClassNotFoundException ignored) {
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Folia 26.2 portal API is unavailable", error);
        }
        folia = bridge;
    }

    private Homeland homeland(World world) {
        return HomelandWorldKeys.NAMESPACE.equals(world.key().namespace())
                ? plugin.getHomelandManager().getHomelandByWorldKey(world.key().value()) : null;
    }

    private String targetKey(World source, PortalType portal, Homeland homeland) {
        HomelandDimension target = portal == PortalType.NETHER
                ? source.getEnvironment() == World.Environment.NETHER
                    ? HomelandDimension.OVERWORLD : HomelandDimension.NETHER
                : source.getEnvironment() == World.Environment.THE_END
                    ? HomelandDimension.OVERWORLD : HomelandDimension.END;
        return target.isUnlocked(homeland) ? target.worldKey(homeland) : null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortalReady(EntityPortalReadyEvent event) {
        if (event.getPortalType() != PortalType.NETHER && event.getPortalType() != PortalType.ENDER) return;
        World source = event.getEntity().getWorld();
        if (!HomelandWorldKeys.NAMESPACE.equals(source.key().namespace())) return;
        Homeland homeland = homeland(source);
        String key = homeland == null ? null : targetKey(source, event.getPortalType(), homeland);
        event.setTargetWorld(key == null || plugin.getHomelandManager().isWorldOperationPending(key)
                ? null : plugin.getHomelandManager().getHomelandWorld(key));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPortalEnter(EntityPortalEnterEvent event) {
        Entity entity = event.getEntity();
        World source = entity.getWorld();
        if (!HomelandWorldKeys.NAMESPACE.equals(source.key().namespace())) return;
        PortalType portal = event.getPortalType();
        if (portal != PortalType.NETHER && portal != PortalType.ENDER) return;
        // Stop Worlds' namespace-based Folia/End handlers before they choose another player's home.
        event.setCancelled(true);
        Homeland homeland = homeland(source);
        if (homeland == null || plugin.getHomelandManager().isWorldOperationPending(source.key().value())) return;
        String key = targetKey(source, portal, homeland);
        if (key == null) {
            message(entity, portal == PortalType.NETHER ? "portal-nether-locked" : "portal-end-locked", homeland);
            return;
        }
        World target = plugin.getHomelandManager().getHomelandWorld(key);
        if (target == null) {
            if (!loading.add(entity.getUniqueId())) return;
            message(entity, "world-loading", homeland);
            plugin.getHomelandManager().loadHomelandWorldByKey(key,
                    world -> loading.remove(entity.getUniqueId()), error -> {
                        loading.remove(entity.getUniqueId());
                        message(entity, "world-not-loaded", homeland);
                    });
            return;
        }
        if (folia == null && portal == PortalType.NETHER) {
            event.setCancelled(false); // Paper uses onPortalReady to select the precise target.
            return;
        }
        if (entity.getPortalCooldown() > 0) return;
        int delay = portal == PortalType.ENDER ? 1 : portalDelay(entity, source);
        PortalTicks state = ticks.computeIfAbsent(entity.getUniqueId(), ignored -> new PortalTicks());
        if (!state.advance(Bukkit.getCurrentTick(), delay)) return;
        Location position = event.getLocation().clone();
        entity.setPortalCooldown(300);
        entity.getScheduler().execute(plugin, () -> {
            if (!entity.getWorld().equals(source)
                    || plugin.getHomelandManager().isWorldOperationPending(key)) return;
            try {
                if (folia != null) folia.teleport(entity, target, position, portal == PortalType.NETHER);
                else teleportEnd(entity, target);
            } catch (ReflectiveOperationException error) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "家园传送门传送失败", error);
            }
        }, () -> ticks.remove(entity.getUniqueId()), 1L);
    }

    private int portalDelay(Entity entity, World source) {
        if (!(entity instanceof Player player)) return 1;
        Integer delay = source.getGameRuleValue(player.getGameMode() == GameMode.CREATIVE
                ? GameRules.PLAYERS_NETHER_PORTAL_CREATIVE_DELAY : GameRules.PLAYERS_NETHER_PORTAL_DEFAULT_DELAY);
        return delay == null ? 80 : Math.max(1, delay);
    }

    private void teleportEnd(Entity entity, World target) {
        if (target.getEnvironment() != World.Environment.THE_END) {
            entity.teleportAsync(target.getSpawnLocation(), TeleportCause.END_PORTAL);
            return;
        }
        Location spawn = new Location(target, 100.5, 49, 0.5, 90, 0);
        Bukkit.getRegionScheduler().execute(plugin, target, 6, 0, () -> {
            for (int x = 98; x <= 102; x++) {
                for (int z = -2; z <= 2; z++) {
                    target.getBlockAt(x, 48, z).setType(Material.OBSIDIAN);
                    for (int y = 49; y <= 51; y++) target.getBlockAt(x, y, z).setType(Material.AIR);
                }
            }
            entity.getScheduler().execute(plugin,
                    () -> entity.teleportAsync(spawn, TeleportCause.END_PORTAL), () -> {}, 1L);
        });
    }

    private void message(Entity entity, String key, Homeland homeland) {
        if (!(entity instanceof Player player) || !messages.add(entity.getUniqueId())) return;
        MessageUtil.sendAsync(player, plugin.getConfigManager().getMessage(key, "name", homeland.getName()), plugin);
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> messages.remove(entity.getUniqueId()), 60L);
    }

    @EventHandler
    public void onEntityRemove(EntityRemoveEvent event) {
        ticks.remove(event.getEntity().getUniqueId());
        loading.remove(event.getEntity().getUniqueId());
    }

    static final class PortalTicks {
        private int lastTick = Integer.MIN_VALUE;
        private int count;

        synchronized boolean advance(int tick, int delay) {
            if (tick == lastTick) return false;
            if (tick != lastTick + 1) count = 0;
            lastTick = tick;
            return ++count >= delay;
        }
    }
}
