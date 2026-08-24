package dev.user.homeland.listener;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.model.Homeland;
import dev.user.homeland.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
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

        String targetWorldKey = toWorld.getKey().getKey();
        boolean homelandWorld = "simplehomeland".equals(toWorld.getKey().getNamespace());
        if (homelandWorld && !plugin.getHomelandManager().isWorldAccessStateReady()) {
            event.setCancelled(true);
            MessageUtil.send(player, plugin.getConfigManager().getMessage("world-access-denied"));
            return;
        }
        if (homelandWorld && plugin.getHomelandManager()
                .getHomelandByWorldKey(targetWorldKey) == null) {
            event.setCancelled(true);
            MessageUtil.send(player, plugin.getConfigManager().getMessage("world-access-denied"));
            return;
        }
        boolean operationPending = plugin.getHomelandManager()
                .isWorldOperationPending(targetWorldKey);
        if (operationPending || (!plugin.getHomelandManager().canEnterWorld(player, targetWorldKey)
                && !plugin.getHomelandManager().isApiBypass(player.getUniqueId()))) {
            event.setCancelled(true);
            MessageUtil.send(player, plugin.getConfigManager().getMessage("world-access-denied"));
        }
    }

    /**
     * 拦截传送门传送：如果目标坐标超出家园世界边界，拒绝传送并提示玩家。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (isOutsideBorder(event.getTo())) {
            event.setCancelled(true);
            MessageUtil.send(event.getPlayer(), plugin.getConfigManager().getMessage(
                    "portal-destination-outside-border", "radius",
                    String.valueOf(plugin.getHomelandManager().getHomelandByWorldKey(
                            event.getTo().getWorld().getKey().getKey()).getBorderRadius())));
        }
    }

    /**
     * 拦截非玩家实体传送门传送：目标超出边界则取消。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (event.getEntity() instanceof Player) return; // 玩家由 onPlayerPortal 处理
        if (isOutsideBorder(event.getTo())) {
            event.setCancelled(true);
        }
    }

    /**
     * 判断目标位置是否超出家园世界边界。
     */
    private boolean isOutsideBorder(org.bukkit.Location to) {
        if (to == null || to.getWorld() == null) return false;
        Homeland homeland = plugin.getHomelandManager().getHomelandByWorldKey(to.getWorld().getKey().getKey());
        if (homeland == null) return false;
        int radius = homeland.getBorderRadius();
        return Math.abs(to.getX()) > radius || Math.abs(to.getZ()) > radius;
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
        if (plugin.getHomelandManager().isWorldOperationPending(homeland.getWorldKey())) return;

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

        plugin.getHomelandManager().loadHomelandWorldByKey(targetWorldKey, world -> {
            if (world == null) {
                MessageUtil.send(player, plugin.getConfigManager().getMessage("world-not-loaded"));
                portalLoading.remove(player.getUniqueId());
                return;
            }
            // 下一 tick worlds 插件会发现目标世界已加载，正常处理传送。
            portalLoading.remove(player.getUniqueId());
        }, error -> {
            portalLoading.remove(player.getUniqueId());
            MessageUtil.send(player, plugin.getConfigManager().getMessage(
                    error instanceof dev.user.homeland.manager.HomelandOperationCoordinator.BusyException
                            ? "homeland-operation-busy" : "world-not-loaded"));
        });
    }

    private void sendCooledMessage(Player player, String key, String homelandName) {
        if (!messageCooldown.add(player.getUniqueId())) return;
        MessageUtil.send(player, plugin.getConfigManager().getMessage(key, "name", homelandName));
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> messageCooldown.remove(player.getUniqueId()), 60L);
    }
}
