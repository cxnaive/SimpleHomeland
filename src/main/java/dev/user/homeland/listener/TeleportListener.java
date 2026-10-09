package dev.user.homeland.listener;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.model.Homeland;
import dev.user.homeland.util.MessageUtil;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;


public class TeleportListener implements Listener {

    private final SimpleHomelandPlugin plugin;

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
        boolean homelandWorld = "homelands".equals(toWorld.getKey().getNamespace());
        if (homelandWorld && !plugin.getHomelandManager().isWorldAccessStateReady()) {
            event.setCancelled(true);
            MessageUtil.send(player, plugin.getConfigManager().getMessage(
                    plugin.getHomelandManager().isStartupMigrationActive()
                            ? "world-migration-in-progress" : "world-access-denied"));
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

}
