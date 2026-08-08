package dev.user.homeland.compat;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.model.VisitorFlag;
import dev.user.homeland.util.MessageUtil;
import net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.libraries.antigrieflib.AntiGriefCompatibility;
import net.momirealms.craftengine.libraries.antigrieflib.Flag;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * CraftEngine 兼容层。
 * 通过 CE 的 AntiGrief SPI 保护自定义方块/家具的破坏与放置，
 * 通过 FurnitureInteractEvent 保护家具右键互动（SPI 未覆盖该路径）。
 */
public class CraftEngineCompat implements AntiGriefCompatibility {

    private final SimpleHomelandPlugin plugin;

    private CraftEngineCompat(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
    }

    /** 注册 anti-grief provider 与家具互动监听器。 */
    public static void register(SimpleHomelandPlugin plugin) {
        CraftEngineCompat compat = new CraftEngineCompat(plugin);
        BukkitCraftEngine.instance().registerProtectionProvider(compat);
        plugin.getServer().getPluginManager().registerEvents(compat.new FurnitureInteractListener(), plugin);
    }

    @Override
    public void init() {
    }

    @Override
    public Plugin plugin() {
        return plugin;
    }

    @Override
    public <T> boolean test(Player player, Flag<T> flag, T value) {
        VisitorFlag visitorFlag = mapFlag(flag);
        if (visitorFlag == null) return true;

        String worldKey;
        if (value instanceof Location location) {
            if (location.getWorld() == null) return true;
            worldKey = location.getWorld().getKey().getKey();
        } else if (value instanceof Entity entity) {
            worldKey = entity.getWorld().getKey().getKey();
        } else {
            return true;
        }
        return plugin.getHomelandManager().checkVisitorFlag(player, worldKey, visitorFlag);
    }

    /** CraftEngine flag → SimpleHomeland 访客权限映射 */
    private VisitorFlag mapFlag(Flag<?> flag) {
        if (flag == Flag.PLACE) return VisitorFlag.PLACE;
        if (flag == Flag.BREAK) return VisitorFlag.ENTITY_DAMAGE;      // 拆除家具
        if (flag == Flag.INTERACT) return VisitorFlag.INTERACT;
        if (flag == Flag.OPEN_DOOR) return VisitorFlag.INTERACT;
        if (flag == Flag.USE_BUTTON) return VisitorFlag.INTERACT;
        if (flag == Flag.USE_PRESSURE_PLATE) return VisitorFlag.INTERACT;
        if (flag == Flag.OPEN_CONTAINER) return VisitorFlag.CONTAINER;
        if (flag == Flag.INTERACT_ENTITY) return VisitorFlag.ENTITY;
        if (flag == Flag.DAMAGE_ENTITY) return VisitorFlag.ENTITY_DAMAGE;
        return null;
    }

    /** 家具右键互动（坐沙发、切换形态等）在 antiGrief SPI 之外，需单独拦截。 */
    private class FurnitureInteractListener implements Listener {

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onFurnitureInteract(FurnitureInteractEvent event) {
            Player player = event.getPlayer();
            Location location = event.location();
            if (location.getWorld() == null) return;
            String worldKey = location.getWorld().getKey().getKey();
            if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.ENTITY)) {
                event.setCancelled(true);
                MessageUtil.send(player, plugin.getConfigManager().getMessage("visitor-flag-denied"));
            }
        }
    }
}
