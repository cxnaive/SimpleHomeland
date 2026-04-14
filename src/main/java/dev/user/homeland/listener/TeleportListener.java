package dev.user.homeland.listener;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.model.Homeland;
import dev.user.homeland.util.MessageUtil;
import net.thenextlvl.worlds.api.WorldsProvider;
import net.thenextlvl.worlds.api.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TeleportListener implements Listener {

    private final SimpleHomelandPlugin plugin;
    // 正在等待维度加载的玩家，防止重复触发加载
    private final Set<UUID> portalLoading = ConcurrentHashMap.newKeySet();
    // 消息冷却，避免每tick刷屏
    private final Set<UUID> messageCooldown = ConcurrentHashMap.newKeySet();

    public TeleportListener(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        World toWorld = event.getTo().getWorld();
        if (toWorld == null) return;

        World fromWorld = event.getFrom().getWorld();
        if (fromWorld.equals(toWorld)) return;

        Player player = event.getPlayer();

        if (!plugin.getHomelandManager().canEnterWorld(player, toWorld.getKey().getKey())) {
            event.setCancelled(true);
            MessageUtil.send(player, plugin.getConfigManager().getMessage("world-access-denied"));
        }
    }

    /**
     * 在 worlds 插件（HIGH 优先级）之前处理家园传送门。
     * 不取消事件，让 worlds 插件正常处理传送（包括传送冷却）。
     * 只负责：发送提示消息 + 异步加载未加载的维度。
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPortalEnter(EntityPortalEnterEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        World fromWorld = event.getLocation().getWorld();
        if (fromWorld == null) return;

        Homeland homeland = plugin.getHomelandManager().getHomelandByWorldKey(fromWorld.getKey().getKey());
        if (homeland == null) return; // 非家园世界

        PortalType portalType = event.getPortalType();
        boolean isNether = portalType == PortalType.NETHER;
        boolean isEnd = portalType == PortalType.ENDER;
        if (!isNether && !isEnd) return;

        // ---- 维度未解锁：发送提示（worlds 插件也会因为无 link 而不传送）----
        if (isNether && !homeland.hasNether()) {
            sendCooledMessage(player, "portal-nether-locked", homeland.getName());
            return;
        }
        if (isEnd && !homeland.hasEnd()) {
            sendCooledMessage(player, "portal-end-locked", homeland.getName());
            return;
        }

        // ---- 维度已解锁，检查是否已加载 ----
        String targetWorldKey = homeland.getWorldKey() + (isNether ? "_nether" : "_the_end");
        World targetWorld = plugin.getHomelandManager().getHomelandWorld(targetWorldKey);
        if (targetWorld != null) return; // 已加载，完全交给 worlds 插件

        // ---- 未加载：启动异步加载（去重）----
        if (!portalLoading.add(player.getUniqueId())) return;

        MessageUtil.send(player, plugin.getConfigManager().getMessage("world-loading"));

        WorldsProvider provider = plugin.getWorldsProvider();
        if (provider == null) {
            portalLoading.remove(player.getUniqueId());
            return;
        }

        java.nio.file.Path worldPath = provider.levelView().getWorldContainer()
                .resolve("homelands/" + targetWorldKey);

        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            // 二次检查
            if (plugin.getHomelandManager().getHomelandWorld(targetWorldKey) != null) {
                portalLoading.remove(player.getUniqueId());
                return;
            }

            var builderOpt = provider.levelView().read(worldPath);
            if (builderOpt.isEmpty()) {
                MessageUtil.send(player, plugin.getConfigManager().getMessage("world-not-loaded"));
                portalLoading.remove(player.getUniqueId());
                return;
            }

            Level level = builderOpt.get().build();
            level.createAsync().thenAccept(world -> {
                world.setAutoSave(true);
                provider.levelView().setEnabled(world, true);

                // 恢复 WorldBorder
                Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                    int radius = homeland.getBorderRadius() * 2;
                    world.getWorldBorder().setCenter(0, 0);
                    world.getWorldBorder().setSize(radius);
                });

                // 加载完成，玩家仍在传送门中
                // 下一 tick worlds 插件会发现目标世界已加载，正常处理传送
                plugin.getHomelandManager().touchLastPlayerTime(homeland.getWorldKey());
                portalLoading.remove(player.getUniqueId());
            }).exceptionally(throwable -> {
                plugin.getLogger().severe("加载家园维度失败: " + throwable.getMessage());
                MessageUtil.send(player, plugin.getConfigManager().getMessage("world-not-loaded"));
                portalLoading.remove(player.getUniqueId());
                return null;
            });
        });
    }

    private void sendCooledMessage(Player player, String key, String homelandName) {
        if (!messageCooldown.add(player.getUniqueId())) return;
        MessageUtil.send(player, plugin.getConfigManager().getMessage(key, "name", homelandName));
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> messageCooldown.remove(player.getUniqueId()), 60L);
    }
}
