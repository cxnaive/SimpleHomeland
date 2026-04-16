package dev.user.homeland.listener;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.model.Homeland;
import net.thenextlvl.worlds.api.WorldsProvider;
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
 * - 子维度：重新关联传送门 link + 从主世界同步游戏规则 + 同步难度
 */
public class WorldLoadListener implements Listener {

    private final SimpleHomelandPlugin plugin;

    public WorldLoadListener(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        World world = event.getWorld();
        String worldKey = world.getKey().getKey();

        Homeland homeland = plugin.getHomelandManager().getHomelandByWorldKey(worldKey);
        if (homeland == null) return;

        // 开启自动保存
        world.setAutoSave(true);

        // 恢复 WorldBorder
        int borderRadius = homeland.getBorderRadius();
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            world.getWorldBorder().setCenter(0, 0);
            world.getWorldBorder().setSize(borderRadius * 2);
        });

        // 重新关联传送门 link
        String baseWorldKey = homeland.getWorldKey();
        WorldsProvider provider = plugin.getWorldsProvider();

        if (worldKey.equals(baseWorldKey)) {
            // 主世界加载时：关联已加载的子维度
            if (provider != null && homeland.hasNether()) {
                World nether = plugin.getHomelandManager().getHomelandWorld(baseWorldKey + "_nether");
                if (nether != null) {
                    provider.linkProvider().link(world, nether);
                }
            }
            if (provider != null && homeland.hasEnd()) {
                World end = plugin.getHomelandManager().getHomelandWorld(baseWorldKey + "_the_end");
                if (end != null) {
                    provider.linkProvider().link(world, end);
                }
            }
        } else {
            // 子维度加载时：关联主世界 + 同步游戏规则 + 同步难度
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                World overworld = plugin.getHomelandManager().getHomelandWorld(baseWorldKey);
                if (overworld != null) {
                    if (provider != null) {
                        provider.linkProvider().link(overworld, world);
                    }
                    plugin.getHomelandManager().copyGamerules(overworld, world);
                    plugin.getHomelandManager().copyDifficulty(overworld, world);
                } else {
                    plugin.getHomelandManager().applyDefaultGamerules(world);
                    plugin.getHomelandManager().applyDefaultDifficulty(world);
                }
            });
        }
    }
}
