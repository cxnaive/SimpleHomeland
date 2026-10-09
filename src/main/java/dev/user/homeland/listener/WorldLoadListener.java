package dev.user.homeland.listener;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.manager.HomelandWorldSettings;
import dev.user.homeland.model.Homeland;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

/**
 * 监听世界加载事件，恢复家园世界的设置。
 *
 * 每次世界加载时：
 * - 开启自动保存
 * - 恢复 WorldBorder
 * - 子维度：从主世界同步游戏规则和难度
 */
public class WorldLoadListener implements Listener {

    private final SimpleHomelandPlugin plugin;

    public WorldLoadListener(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        World world = event.getWorld();
        if (!"homelands".equals(world.getKey().getNamespace())) return;
        String worldKey = world.getKey().getKey();

        Homeland homeland = plugin.getHomelandManager().getHomelandByWorldKey(worldKey);
        if (homeland == null) return;
        if (plugin.getHomelandManager().isWorldOperationPending(worldKey)) return;

        // Managed loads apply this before completing their shared future. This
        // listener covers already completed worlds loaded by another plugin.
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            if (!plugin.getHomelandManager().isWorldOperationPending(worldKey)) {
                HomelandWorldSettings.applyLoadedWorld(plugin, homeland, world);
            }
        });
    }
}
