package dev.user.homeland.listener;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.model.Homeland;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFadeEvent;

/**
 * 自定义设置事件监听器
 * 处理非原生 GameRule 的自定义规则，通过事件监听实现。
 */
public class CustomSettingListener implements Listener {

    private final SimpleHomelandPlugin plugin;

    public CustomSettingListener(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        Material type = event.getBlock().getType();
        // 只处理冰和雪层
        if (type != Material.ICE && type != Material.SNOW && type != Material.FROSTED_ICE) return;

        String worldKey = event.getBlock().getWorld().getKey().getKey();
        Homeland homeland = plugin.getHomelandManager().getHomelandByWorldKey(worldKey);
        if (homeland == null) return; // 非家园世界

        boolean prevent = homeland.getCustomSettings().get("PREVENT_ICE_SNOW_MELTING", false);
        if (prevent) {
            event.setCancelled(true);
        }
    }
}
