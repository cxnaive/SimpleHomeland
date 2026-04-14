package dev.user.homeland.listener;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.config.ConfigManager;
import dev.user.homeland.gui.HomelandCreateGUI;
import dev.user.homeland.model.Homeland;
import dev.user.homeland.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class PlayerListener implements Listener {

    private final SimpleHomelandPlugin plugin;

    public PlayerListener(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getHomelandManager().onPlayerJoin(player.getUniqueId());

        // 延迟1 tick检查，等待家园数据加载完成
        player.getScheduler().execute(plugin, () -> {
            checkAndEject(player);
        }, () -> {}, 1L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.getHomelandManager().onPlayerQuit(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (event.isAnchorSpawn() || event.isBedSpawn()) return;

        Player player = event.getPlayer();
        World respawnWorld = event.getRespawnLocation().getWorld();
        if (respawnWorld == null) return;

        Homeland homeland = plugin.getHomelandManager().getHomelandByWorldKey(respawnWorld.getKey().getKey());
        if (homeland == null) return; // 非家园世界

        if (!plugin.getHomelandManager().canEnterWorld(player, respawnWorld.getKey().getKey())) {
            // 玩家无权在此家园重生，重定向到主世界
            World mainWorld = Bukkit.getWorlds().get(0);
            event.setRespawnLocation(mainWorld.getSpawnLocation());
            player.getScheduler().execute(plugin, () -> {
                MessageUtil.send(player, plugin.getConfigManager().getMessage("homeland-eject"));
            }, () -> {}, 1L);
        }
    }

    @EventHandler
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        ConfigManager config = plugin.getConfigManager();

        // 家园命名输入
        if (plugin.getHomelandManager().isNaming(uuid)) {
            event.setCancelled(true);
            handleNamingInput(player, uuid, event.getMessage().trim(), config);
            return;
        }

        // 管理员为他人命名输入
        if (plugin.getHomelandManager().isAdminNaming(uuid)) {
            event.setCancelled(true);
            handleAdminNamingInput(player, uuid, event.getMessage().trim(), config);
            return;
        }

        // 邀请/取消邀请玩家名称输入
        if (plugin.getHomelandManager().isInviteInput(uuid)) {
            event.setCancelled(true);
            handleInviteInput(player, uuid, event.getMessage().trim(), config);
            return;
        }
    }

    private void handleNamingInput(Player player, UUID uuid, String input, ConfigManager config) {
        // 取消操作
        if (input.equalsIgnoreCase("cancel")) {
            plugin.getHomelandManager().finishNaming(uuid);
            MessageUtil.sendAsync(player, config.getMessage("gui.create-cancelled"), plugin);
            return;
        }

        // 验证名称格式
        if (input.length() < 2 || input.length() > 32 || !input.matches("[a-zA-Z0-9_\\u4e00-\\u9fa5]+")) {
            MessageUtil.sendAsync(player, config.getMessage("homeland-name-invalid"), plugin);
            return;
        }

        // 检查数量上限
        if (plugin.getHomelandManager().getHomelandCount(uuid) >= config.getMaxHomelands()) {
            plugin.getHomelandManager().finishNaming(uuid);
            MessageUtil.sendAsync(player, config.getMessage("homeland-limit-reached", "max", String.valueOf(config.getMaxHomelands())), plugin);
            return;
        }

        // 检查重名
        Optional<Homeland> existing = plugin.getHomelandManager().getHomeland(uuid, input);
        if (existing.isPresent()) {
            MessageUtil.sendAsync(player, config.getMessage("homeland-name-exists", "name", input), plugin);
            return;
        }

        // 名称合法，结束命名状态，打开地形选择 GUI
        plugin.getHomelandManager().finishNaming(uuid);
        player.getScheduler().execute(plugin, () -> {
            HomelandCreateGUI.open(plugin, player, input);
        }, () -> {}, 1L);
    }

    private void handleAdminNamingInput(Player admin, UUID adminUuid, String input, ConfigManager config) {
        if (input.equalsIgnoreCase("cancel")) {
            plugin.getHomelandManager().finishAdminNaming(adminUuid);
            MessageUtil.sendAsync(admin, config.getMessage("gui.create-cancelled"), plugin);
            return;
        }

        // 验证名称格式
        if (input.length() < 2 || input.length() > 32 || !input.matches("[a-zA-Z0-9_\\u4e00-\\u9fa5]+")) {
            MessageUtil.sendAsync(admin, config.getMessage("homeland-name-invalid"), plugin);
            return;
        }

        Object[] state = plugin.getHomelandManager().getAdminNamingState(adminUuid);
        if (state == null) return;
        UUID targetUuid = (UUID) state[0];
        String targetName = (String) state[1];

        // 检查目标玩家重名
        Optional<Homeland> existing = plugin.getHomelandManager().getHomeland(targetUuid, input);
        if (existing.isPresent()) {
            MessageUtil.sendAsync(admin, config.getMessage("homeland-name-exists", "name", input), plugin);
            return;
        }

        plugin.getHomelandManager().finishAdminNaming(adminUuid);
        admin.getScheduler().execute(plugin, () -> {
            HomelandCreateGUI.openAdmin(plugin, admin, input, targetUuid, targetName);
        }, () -> {}, 1L);
    }

    private void handleInviteInput(Player player, UUID uuid, String input, ConfigManager config) {
        // 取消操作
        if (input.equalsIgnoreCase("cancel")) {
            plugin.getHomelandManager().finishInviteInput(uuid);
            MessageUtil.sendAsync(player, config.getMessage("gui.invite-cancelled"), plugin);
            return;
        }

        Object[] state = plugin.getHomelandManager().getInviteInputState(uuid);
        if (state == null) return;
        String homelandName = (String) state[0];
        boolean isUninvite = (Boolean) state[1];

        // 查找目标玩家（在线）
        Player target = plugin.getServer().getPlayer(input);
        if (target == null) {
            MessageUtil.sendAsync(player, config.getMessage("admin-player-not-found", "name", input), plugin);
            return;
        }

        plugin.getHomelandManager().finishInviteInput(uuid);

        if (isUninvite) {
            player.getScheduler().execute(plugin, () -> {
                plugin.getHomelandManager().uninvitePlayer(player, homelandName, target.getUniqueId(), target.getName(), () -> {}, () -> {});
            }, () -> {}, 1L);
        } else {
            player.getScheduler().execute(plugin, () -> {
                plugin.getHomelandManager().invitePlayer(player, homelandName, target.getUniqueId(), target.getName(), () -> {}, () -> {});
            }, () -> {}, 1L);
        }
    }

    /**
     * 检查玩家是否在无权进入的家园世界中，如果是则传送到主世界。
     */
    private void checkAndEject(Player player) {
        World world = player.getWorld();
        String worldKey = world.getKey().getKey();
        Homeland homeland = plugin.getHomelandManager().getHomelandByWorldKey(worldKey);
        if (homeland == null) return; // 非家园世界

        if (!plugin.getHomelandManager().canEnterWorld(player, worldKey)) {
            World mainWorld = Bukkit.getWorlds().get(0);
            player.teleportAsync(mainWorld.getSpawnLocation());
            MessageUtil.send(player, plugin.getConfigManager().getMessage("homeland-eject"));
        }
    }
}
