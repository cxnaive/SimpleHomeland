package dev.user.homeland.listener;

import cn.handyplus.warp.event.WarpTpEvent;
import dev.user.homeland.SimpleHomelandPlugin;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class PlayerWarpListener implements Listener {

    private final SimpleHomelandPlugin plugin;

    public PlayerWarpListener(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onWarpTp(WarpTpEvent event) {
        Location loc = event.getLocation();
        World world = loc.getWorld();
        Player player = event.getPlayer();
        String warpName = event.getWarpName();

        plugin.getLogger().info("[PlayerWarp Debug] WarpTpEvent fired: "
                + "player=" + player.getName()
                + ", warpName=" + warpName
                + ", world=" + (world != null ? world.getName() + " (" + world.getKey() + ")" : "null")
                + ", loc=" + loc.getX() + "/" + loc.getY() + "/" + loc.getZ()
                + ", async=" + event.isAsynchronous()
                + ", cancelled=" + event.isCancelled());
    }
}
