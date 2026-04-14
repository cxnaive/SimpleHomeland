package dev.user.homeland.listener;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.model.Homeland;
import dev.user.homeland.util.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public class CommandBlockListener implements Listener {

    private final SimpleHomelandPlugin plugin;

    public CommandBlockListener(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfigManager().isCommandBlockEnabled()) return;

        Player player = event.getPlayer();

        // admin bypass
        if (player.hasPermission("simplehomeland.admin")) return;

        // 检查是否在家园世界
        String worldKey = player.getWorld().getKey().getKey();
        Homeland homeland = plugin.getHomelandManager().getHomelandByWorldKey(worldKey);
        if (homeland == null) return;

        // 家园主人 bypass（可配置）
        if (!plugin.getConfigManager().isCommandBlockOwner()
                && homeland.getOwnerUuid().equals(player.getUniqueId())) return;

        // 提取命令名
        String message = event.getMessage().substring(1); // 去掉 "/"
        String[] parts = message.split(" ", 2);
        String cmd = parts[0].toLowerCase();
        // 处理 "minecraft:tp" 等命名空间前缀
        if (cmd.contains(":")) {
            cmd = cmd.substring(cmd.indexOf(':') + 1);
        }

        if (!plugin.getConfigManager().getBlockedCommands().contains(cmd)) return;

        // 拦截
        event.setCancelled(true);
        MessageUtil.send(player, plugin.getConfigManager().getMessage("command-blocked"));
    }
}
