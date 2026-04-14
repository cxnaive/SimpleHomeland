package dev.user.homeland.command;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.model.Homeland;
import dev.user.homeland.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class HomelandCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_USE = "simplehomeland.use";
    private static final String PERM_CREATE = "simplehomeland.homeland.create";
    private static final String PERM_DELETE = "simplehomeland.homeland.delete";
    private static final String PERM_HOME = "simplehomeland.homeland.home";
    private static final String PERM_LIST = "simplehomeland.homeland.list";
    private static final String PERM_BORDER = "simplehomeland.homeland.border";
    private static final String PERM_UNLOCK = "simplehomeland.homeland.unlock";
    private static final String PERM_UNLOCK_NETHER = "simplehomeland.homeland.unlock.nether";
    private static final String PERM_UNLOCK_END = "simplehomeland.homeland.unlock.end";
    private static final String PERM_ADMIN = "simplehomeland.admin";

    private static final List<String> SUB_COMMANDS = List.of(
            "create", "delete", "home", "list", "border", "unlock", "lock", "invite", "uninvite", "public", "back", "admin", "reload"
    );

    private static final List<String> VALID_WORLD_TYPES = List.of("default", "void", "flat");

    private final SimpleHomelandPlugin plugin;

    public HomelandCommand(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                dev.user.homeland.gui.HomelandGUI.open(plugin, player);
            } else {
                sendUsage(sender);
            }
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "create" -> handleCreate(sender, args);
            case "delete" -> handleDelete(sender, args);
            case "home" -> handleHome(sender, args);
            case "list" -> handleList(sender, args);
            case "border" -> handleBorder(sender, args);
            case "unlock" -> handleUnlock(sender, args);
            case "lock" -> handleLock(sender, args);
            case "invite" -> handleInvite(sender, args);
            case "uninvite" -> handleUninvite(sender, args);
            case "public" -> handlePublic(sender, args);
            case "back" -> handleBack(sender);
            case "admin" -> handleAdmin(sender, args);
            case "reload" -> handleReload(sender);
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }

    // ==================== 玩家命令 ====================

    private boolean handleCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("player-only"));
            return true;
        }
        if (!player.hasPermission(PERM_CREATE)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.create"));
            return true;
        }

        String name = args[1];
        String worldTypeKey = args.length >= 3 ? args[2].toLowerCase() : plugin.getConfigManager().getDefaultWorldType();

        if (!isValidWorldType(worldTypeKey)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("invalid-world-type"));
            return true;
        }

        plugin.getHomelandManager().createHomeland(player, name, worldTypeKey, () -> {}, () -> {});
        return true;
    }

    private boolean handleDelete(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("player-only"));
            return true;
        }
        if (!player.hasPermission(PERM_DELETE)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.delete"));
            return true;
        }

        String name = args[1];
        plugin.getHomelandManager().deleteHomeland(player, name, () -> {}, () -> {});
        return true;
    }

    private boolean handleHome(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("player-only"));
            return true;
        }
        if (!player.hasPermission(PERM_HOME)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("no-permission"));
            return true;
        }

        if (args.length < 2) {
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.home"));
            return true;
        }

        // /homeland home <玩家> <名称> — 管理员传送到玩家家园
        if (args.length >= 3 && player.hasPermission(PERM_ADMIN)) {
            String targetName = args[1];
            String homelandName = args[2];

            @SuppressWarnings("deprecation")
            org.bukkit.OfflinePlayer offlineTarget = plugin.getServer().getOfflinePlayer(targetName);
            if (!offlineTarget.hasPlayedBefore()) {
                sendMessage(sender, plugin.getConfigManager().getMessage("admin-player-not-found", "name", targetName));
                return true;
            }

            plugin.getHomelandManager().teleportToHomeland(player, offlineTarget.getUniqueId(), homelandName);
            return true;
        }

        // /homeland home <名称> — 传送到自己的家园
        String name = args[1];
        plugin.getHomelandManager().teleportToHomeland(player, name);
        return true;
    }

    private boolean handleList(CommandSender sender, String[] args) {
        // /homeland list [玩家] — 管理员可查看指定玩家
        if (args.length >= 2 && sender.hasPermission(PERM_ADMIN)) {
            String targetName = args[1];
            @SuppressWarnings("deprecation")
            org.bukkit.OfflinePlayer offlineTarget = plugin.getServer().getOfflinePlayer(targetName);
            UUID targetUuid = offlineTarget.getUniqueId();

            sendMessage(sender, plugin.getConfigManager().getMessage("homeland-list-header"));
            plugin.getHomelandManager().getOrLoadHomelandsAsync(targetUuid, homelands -> {
                if (homelands.isEmpty()) {
                    sendMessage(sender, plugin.getConfigManager().getMessage("homeland-list-empty"));
                } else {
                    int index = 1;
                    for (Homeland h : homelands) {
                        String entry = plugin.getConfigManager().getMessage("homeland-list-entry",
                                "index", String.valueOf(index),
                                "name", h.getName(),
                                "border", String.valueOf(h.getBorderRadius()));
                        sendMessage(sender, entry);
                        index++;
                    }
                }
            });
            return true;
        }

        if (!(sender instanceof Player player)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("player-only"));
            return true;
        }
        if (!player.hasPermission(PERM_LIST)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("no-permission"));
            return true;
        }

        List<Homeland> homelands = plugin.getHomelandManager().getHomelands(player.getUniqueId());

        sendMessage(sender, plugin.getConfigManager().getMessage("homeland-list-header"));

        if (homelands.isEmpty()) {
            sendMessage(sender, plugin.getConfigManager().getMessage("homeland-list-empty"));
        } else {
            int index = 1;
            for (Homeland h : homelands) {
                String entry = plugin.getConfigManager().getMessage("homeland-list-entry",
                        "index", String.valueOf(index),
                        "name", h.getName(),
                        "border", String.valueOf(h.getBorderRadius()));
                sendMessage(sender, entry);
                index++;
            }
        }
        return true;
    }

    private boolean handleBorder(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("player-only"));
            return true;
        }
        if (!player.hasPermission(PERM_BORDER)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("no-permission"));
            return true;
        }

        // /homeland border <名称> expand
        if (args.length < 3) {
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.border"));
            return true;
        }

        String name = args[1];
        String action = args[2].toLowerCase();

        if (!action.equals("expand")) {
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.border"));
            return true;
        }

        plugin.getHomelandManager().expandBorder(player, name, () -> {}, () -> {});
        return true;
    }

    private boolean handleUnlock(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("player-only"));
            return true;
        }
        if (!player.hasPermission(PERM_UNLOCK)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("no-permission"));
            return true;
        }

        // /homeland unlock <名称> <nether|end>
        if (args.length < 3) {
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.unlock"));
            return true;
        }

        String name = args[1];
        String dimension = args[2].toLowerCase();

        switch (dimension) {
            case "nether" -> {
                if (!player.hasPermission(PERM_UNLOCK_NETHER)) {
                    sendMessage(sender, plugin.getConfigManager().getMessage("no-permission"));
                    return true;
                }
                plugin.getHomelandManager().unlockNether(player, name, () -> {}, () -> {});
            }
            case "end" -> {
                if (!player.hasPermission(PERM_UNLOCK_END)) {
                    sendMessage(sender, plugin.getConfigManager().getMessage("no-permission"));
                    return true;
                }
                plugin.getHomelandManager().unlockEnd(player, name, () -> {}, () -> {});
            }
            default -> sendMessage(sender, plugin.getConfigManager().getMessage("usage.unlock"));
        }
        return true;
    }

    // ==================== 删除维度 ====================

    private boolean handleLock(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("player-only"));
            return true;
        }
        if (!player.hasPermission(PERM_UNLOCK)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("no-permission"));
            return true;
        }

        // /homeland lock <名称> <nether|end>
        if (args.length < 3) {
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.lock"));
            return true;
        }

        String name = args[1];
        String dimension = args[2].toLowerCase();

        switch (dimension) {
            case "nether" -> {
                if (!player.hasPermission(PERM_UNLOCK_NETHER)) {
                    sendMessage(sender, plugin.getConfigManager().getMessage("no-permission"));
                    return true;
                }
                plugin.getHomelandManager().lockNether(player, name, () -> {}, () -> {});
            }
            case "end" -> {
                if (!player.hasPermission(PERM_UNLOCK_END)) {
                    sendMessage(sender, plugin.getConfigManager().getMessage("no-permission"));
                    return true;
                }
                plugin.getHomelandManager().lockEnd(player, name, () -> {}, () -> {});
            }
            default -> sendMessage(sender, plugin.getConfigManager().getMessage("usage.lock"));
        }
        return true;
    }

    // ==================== 公开/私有 ====================

    private boolean handlePublic(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("player-only"));
            return true;
        }
        if (!player.hasPermission(PERM_USE)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("no-permission"));
            return true;
        }
        // /homeland public <名称>
        if (args.length < 2) {
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.public"));
            return true;
        }

        String name = args[1];
        plugin.getHomelandManager().togglePublic(player, name, () -> {}, () -> {});
        return true;
    }

    private boolean handleBack(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("player-only"));
            return true;
        }
        plugin.getHomelandManager().teleportToMainWorld(player);
        return true;
    }

    // ==================== 邀请命令 ====================

    private boolean handleInvite(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("player-only"));
            return true;
        }
        if (!player.hasPermission(PERM_USE)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("no-permission"));
            return true;
        }
        // /homeland invite <名称> <玩家>
        if (args.length < 3) {
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.invite"));
            return true;
        }

        String homelandName = args[1];
        String targetName = args[2];

        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer offlineTarget = plugin.getServer().getOfflinePlayer(targetName);
        if (!offlineTarget.hasPlayedBefore()) {
            sendMessage(sender, plugin.getConfigManager().getMessage("admin-player-not-found", "name", targetName));
            return true;
        }

        plugin.getHomelandManager().invitePlayer(player, homelandName, offlineTarget.getUniqueId(), targetName,
                () -> {}, () -> {});
        return true;
    }

    private boolean handleUninvite(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("player-only"));
            return true;
        }
        if (!player.hasPermission(PERM_USE)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("no-permission"));
            return true;
        }
        // /homeland uninvite <名称> <玩家>
        if (args.length < 3) {
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.uninvite"));
            return true;
        }

        String homelandName = args[1];
        String targetName = args[2];

        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer offlineTarget = plugin.getServer().getOfflinePlayer(targetName);
        if (!offlineTarget.hasPlayedBefore()) {
            sendMessage(sender, plugin.getConfigManager().getMessage("admin-player-not-found", "name", targetName));
            return true;
        }

        plugin.getHomelandManager().uninvitePlayer(player, homelandName, offlineTarget.getUniqueId(), targetName,
                () -> {}, () -> {});
        return true;
    }

    // ==================== 管理员命令 ====================

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("no-permission"));
            return true;
        }

        if (args.length < 2) {
            if (sender instanceof Player player) {
                dev.user.homeland.gui.HomelandAdminGUI.open(plugin, player);
            } else {
                sendAdminUsage(sender);
            }
            return true;
        }

        return switch (args[1].toLowerCase()) {
            case "create" -> handleAdminCreate(sender, args);
            case "delete" -> handleAdminDelete(sender, args);
            case "tp" -> handleAdminTp(sender, args);
            case "border" -> handleAdminBorder(sender, args);
            case "unlock" -> handleAdminUnlock(sender, args);
            case "lock" -> handleAdminLock(sender, args);
            default -> {
                sendAdminUsage(sender);
                yield true;
            }
        };
    }

    private boolean handleAdminCreate(CommandSender sender, String[] args) {
        // /homeland admin create <玩家> <名称> [default|void|flat]
        if (args.length < 4) {
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-create"));
            return true;
        }

        String targetName = args[2];
        String homelandName = args[3];
        String worldTypeKey = args.length >= 5 ? args[4].toLowerCase() : plugin.getConfigManager().getDefaultWorldType();

        if (!isValidWorldType(worldTypeKey)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("invalid-world-type"));
            return true;
        }

        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer offlineTarget = plugin.getServer().getOfflinePlayer(targetName);
        if (!offlineTarget.hasPlayedBefore()) {
            sendMessage(sender, plugin.getConfigManager().getMessage("admin-player-not-found", "name", targetName));
            return true;
        }

        // 管理员创建不要求目标玩家在线，只需要 UUID
        UUID targetUuid = offlineTarget.getUniqueId();

        // 先加载目标玩家的家园缓存（如果未加载）
        plugin.getHomelandManager().getOrLoadHomelandsAsync(targetUuid, homelands -> {
            // 缓存加载完成后执行创建
            plugin.getHomelandManager().createHomelandAdmin(sender, targetUuid, homelandName, worldTypeKey,
                    () -> sendMessage(sender, plugin.getConfigManager().getMessage("admin-create-success", "player", targetName, "name", homelandName)),
                    () -> sendMessage(sender, plugin.getConfigManager().getMessage("admin-create-failed")));
        }, () -> {
            sendMessage(sender, plugin.getConfigManager().getMessage("admin-create-failed"));
        });
        return true;
    }

    private boolean handleAdminDelete(CommandSender sender, String[] args) {
        // /homeland admin delete <玩家> <名称>
        if (args.length < 4) {
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-delete"));
            return true;
        }

        String targetName = args[2];
        String homelandName = args[3];

        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer offlineTarget = plugin.getServer().getOfflinePlayer(targetName);
        if (!offlineTarget.hasPlayedBefore()) {
            sendMessage(sender, plugin.getConfigManager().getMessage("admin-player-not-found", "name", targetName));
            return true;
        }
        UUID targetUuid = offlineTarget.getUniqueId();

        var optHomeland = plugin.getHomelandManager().getHomeland(targetUuid, homelandName);
        if (optHomeland.isEmpty()) {
            sendMessage(sender, plugin.getConfigManager().getMessage("homeland-not-found", "name", homelandName));
            return true;
        }

        plugin.getHomelandManager().deleteHomelandAdmin(sender, targetUuid, homelandName,
                () -> sendMessage(sender, plugin.getConfigManager().getMessage("admin-delete-success", "player", targetName, "name", homelandName)),
                () -> sendMessage(sender, plugin.getConfigManager().getMessage("admin-delete-failed")));
        return true;
    }

    private boolean handleAdminTp(CommandSender sender, String[] args) {
        // /homeland admin tp <玩家> <名称>
        if (!(sender instanceof Player player)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("player-only"));
            return true;
        }
        if (args.length < 4) {
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-tp"));
            return true;
        }

        String targetName = args[2];
        String homelandName = args[3];

        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer offlineTarget = plugin.getServer().getOfflinePlayer(targetName);
        if (!offlineTarget.hasPlayedBefore()) {
            sendMessage(sender, plugin.getConfigManager().getMessage("admin-player-not-found", "name", targetName));
            return true;
        }
        UUID targetUuid = offlineTarget.getUniqueId();

        plugin.getHomelandManager().teleportToHomeland(player, targetUuid, homelandName);
        return true;
    }

    private boolean handleAdminBorder(CommandSender sender, String[] args) {
        // /homeland admin border <玩家> <名称> expand
        if (args.length < 5) {
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-border"));
            return true;
        }

        String targetName = args[2];
        String homelandName = args[3];
        String action = args[4].toLowerCase();

        if (!action.equals("expand")) {
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-border"));
            return true;
        }

        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer offlineTarget = plugin.getServer().getOfflinePlayer(targetName);
        if (!offlineTarget.hasPlayedBefore()) {
            sendMessage(sender, plugin.getConfigManager().getMessage("admin-player-not-found", "name", targetName));
            return true;
        }
        UUID targetUuid = offlineTarget.getUniqueId();

        // 先加载目标玩家的家园缓存
        plugin.getHomelandManager().getOrLoadHomelandsAsync(targetUuid, homelands -> {
            var optHomeland = plugin.getHomelandManager().getHomeland(targetUuid, homelandName);
            if (optHomeland.isEmpty()) {
                sendMessage(sender, plugin.getConfigManager().getMessage("homeland-not-found", "name", homelandName));
                return;
            }

            plugin.getHomelandManager().expandBorderAdmin(sender, targetUuid, homelandName,
                    () -> sendMessage(sender, plugin.getConfigManager().getMessage("admin-border-success", "name", homelandName)),
                    () -> sendMessage(sender, plugin.getConfigManager().getMessage("admin-border-failed")));
        }, () -> {
            sendMessage(sender, plugin.getConfigManager().getMessage("admin-border-failed"));
        });
        return true;
    }

    private boolean handleAdminUnlock(CommandSender sender, String[] args) {
        // /homeland admin unlock <玩家> <名称> <nether|end>
        if (args.length < 5) {
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-unlock"));
            return true;
        }

        String targetName = args[2];
        String homelandName = args[3];
        String dimension = args[4].toLowerCase();

        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer offlineTarget = plugin.getServer().getOfflinePlayer(targetName);
        if (!offlineTarget.hasPlayedBefore()) {
            sendMessage(sender, plugin.getConfigManager().getMessage("admin-player-not-found", "name", targetName));
            return true;
        }
        UUID targetUuid = offlineTarget.getUniqueId();

        // 先加载目标玩家的家园缓存
        plugin.getHomelandManager().getOrLoadHomelandsAsync(targetUuid, homelands -> {
            var optHomeland = plugin.getHomelandManager().getHomeland(targetUuid, homelandName);
            if (optHomeland.isEmpty()) {
                sendMessage(sender, plugin.getConfigManager().getMessage("homeland-not-found", "name", homelandName));
                return;
            }

            switch (dimension) {
                case "nether" -> plugin.getHomelandManager().unlockNetherAdmin(sender, targetUuid, homelandName,
                        () -> sendMessage(sender, plugin.getConfigManager().getMessage("admin-unlock-nether-success", "player", targetName, "name", homelandName)),
                        () -> sendMessage(sender, plugin.getConfigManager().getMessage("admin-unlock-nether-failed")));
                case "end" -> plugin.getHomelandManager().unlockEndAdmin(sender, targetUuid, homelandName,
                        () -> sendMessage(sender, plugin.getConfigManager().getMessage("admin-unlock-end-success", "player", targetName, "name", homelandName)),
                        () -> sendMessage(sender, plugin.getConfigManager().getMessage("admin-unlock-end-failed")));
                default -> sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-unlock"));
            }
        }, () -> {
            sendMessage(sender, plugin.getConfigManager().getMessage("admin-unlock-nether-failed"));
        });
        return true;
    }

    private boolean handleAdminLock(CommandSender sender, String[] args) {
        // /homeland admin lock <玩家> <名称> <nether|end>
        if (args.length < 5) {
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-lock"));
            return true;
        }

        String targetName = args[2];
        String homelandName = args[3];
        String dimension = args[4].toLowerCase();

        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer offlineTarget = plugin.getServer().getOfflinePlayer(targetName);
        if (!offlineTarget.hasPlayedBefore()) {
            sendMessage(sender, plugin.getConfigManager().getMessage("admin-player-not-found", "name", targetName));
            return true;
        }
        UUID targetUuid = offlineTarget.getUniqueId();

        plugin.getHomelandManager().getOrLoadHomelandsAsync(targetUuid, homelands -> {
            var optHomeland = plugin.getHomelandManager().getHomeland(targetUuid, homelandName);
            if (optHomeland.isEmpty()) {
                sendMessage(sender, plugin.getConfigManager().getMessage("homeland-not-found", "name", homelandName));
                return;
            }

            switch (dimension) {
                case "nether" -> plugin.getHomelandManager().lockNetherAdmin(sender, targetUuid, homelandName,
                        () -> sendMessage(sender, plugin.getConfigManager().getMessage("admin-lock-nether-success", "player", targetName, "name", homelandName)),
                        () -> sendMessage(sender, plugin.getConfigManager().getMessage("admin-lock-nether-failed")));
                case "end" -> plugin.getHomelandManager().lockEndAdmin(sender, targetUuid, homelandName,
                        () -> sendMessage(sender, plugin.getConfigManager().getMessage("admin-lock-end-success", "player", targetName, "name", homelandName)),
                        () -> sendMessage(sender, plugin.getConfigManager().getMessage("admin-lock-end-failed")));
                default -> sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-lock"));
            }
        }, () -> {
            sendMessage(sender, plugin.getConfigManager().getMessage("admin-lock-nether-failed"));
        });
        return true;
    }

    // ==================== 重载命令 ====================

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("no-permission"));
            return true;
        }
        plugin.reload();
        sendMessage(sender, plugin.getConfigManager().getMessage("config-reloaded"));
        return true;
    }

    // ==================== 帮助信息 ====================

    private void sendUsage(CommandSender sender) {
        sendMessage(sender, plugin.getConfigManager().getMessage("help-header"));
        sendMessage(sender, plugin.getConfigManager().getMessage("usage.create"));
        sendMessage(sender, plugin.getConfigManager().getMessage("usage.delete"));
        sendMessage(sender, plugin.getConfigManager().getMessage("usage.home"));
        sendMessage(sender, plugin.getConfigManager().getMessage("usage.list"));
        sendMessage(sender, plugin.getConfigManager().getMessage("usage.border"));
        sendMessage(sender, plugin.getConfigManager().getMessage("usage.unlock"));
        sendMessage(sender, plugin.getConfigManager().getMessage("usage.lock"));
        sendMessage(sender, plugin.getConfigManager().getMessage("usage.invite"));
        sendMessage(sender, plugin.getConfigManager().getMessage("usage.uninvite"));
        sendMessage(sender, plugin.getConfigManager().getMessage("usage.public"));

        if (sender.hasPermission(PERM_ADMIN)) {
            sendMessage(sender, plugin.getConfigManager().getMessage("admin-help-header"));
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-create"));
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-delete"));
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-tp"));
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-border"));
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-unlock"));
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-lock"));
            sendMessage(sender, plugin.getConfigManager().getMessage("usage.reload"));
        }
    }

    private void sendAdminUsage(CommandSender sender) {
        sendMessage(sender, plugin.getConfigManager().getMessage("admin-help-header"));
        sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-create"));
        sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-delete"));
        sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-tp"));
        sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-border"));
        sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-unlock"));
        sendMessage(sender, plugin.getConfigManager().getMessage("usage.admin-lock"));
        sendMessage(sender, plugin.getConfigManager().getMessage("usage.reload"));
    }

    /**
     * 发送消息给 sender（使用 Adventure Component，Folia 安全方式）
     */
    private void sendMessage(CommandSender sender, String message) {
        MessageUtil.sendAsync(sender, message, plugin);
    }

    private boolean isValidWorldType(String type) {
        return VALID_WORLD_TYPES.contains(type);
    }

    // ==================== Tab 补全 ====================

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String label, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String input = args[0].toLowerCase();
            for (String sub : SUB_COMMANDS) {
                if (sub.startsWith(input)) {
                    boolean playerCmd = List.of("create", "delete", "home", "list", "border", "unlock", "lock", "invite", "uninvite", "public", "back").contains(sub);
                    if (playerCmd || sender.hasPermission(PERM_ADMIN)) {
                        completions.add(sub);
                    }
                }
            }
        } else if (args.length == 2) {
            String subCmd = args[0].toLowerCase();
            String input = args[1].toLowerCase();

            switch (subCmd) {
                case "delete", "border" -> {
                    if (sender instanceof Player player) {
                        for (Homeland h : plugin.getHomelandManager().getHomelands(player.getUniqueId())) {
                            if (h.getName().toLowerCase().startsWith(input)) {
                                completions.add(h.getName());
                            }
                        }
                    }
                }
                case "home" -> {
                    if (sender instanceof Player player) {
                        for (Homeland h : plugin.getHomelandManager().getHomelands(player.getUniqueId())) {
                            if (h.getName().toLowerCase().startsWith(input)) {
                                completions.add(h.getName());
                            }
                        }
                        // 管理员也补全在线玩家名
                        if (sender.hasPermission(PERM_ADMIN)) {
                            for (Player p : plugin.getServer().getOnlinePlayers()) {
                                if (p.getName().toLowerCase().startsWith(input)) {
                                    completions.add(p.getName());
                                }
                            }
                        }
                    }
                }
                case "create" -> {
                    // 创建时不应补全现有家园名，让玩家自己输入新名称
                }
                case "list" -> {
                    // /homeland list <玩家> — 管理员补全在线玩家名
                    if (sender.hasPermission(PERM_ADMIN)) {
                        for (Player p : plugin.getServer().getOnlinePlayers()) {
                            if (p.getName().toLowerCase().startsWith(input)) {
                                completions.add(p.getName());
                            }
                        }
                    }
                }
                case "unlock", "lock", "invite", "uninvite", "public" -> {
                    if (sender instanceof Player player) {
                        for (Homeland h : plugin.getHomelandManager().getHomelands(player.getUniqueId())) {
                            if (h.getName().toLowerCase().startsWith(input)) {
                                completions.add(h.getName());
                            }
                        }
                    }
                }
                case "admin" -> {
                    if (sender.hasPermission(PERM_ADMIN)) {
                        for (String sub : List.of("create", "delete", "tp", "border", "unlock", "lock")) {
                            if (sub.startsWith(input)) {
                                completions.add(sub);
                            }
                        }
                    }
                }
            }
        } else if (args.length == 3) {
            String subCmd = args[0].toLowerCase();

            if (subCmd.equals("border")) {
                String input = args[2].toLowerCase();
                if ("expand".startsWith(input)) {
                    completions.add("expand");
                }
            } else if (subCmd.equals("create")) {
                // /homeland create <名称> [default|void|flat]
                String input = args[2].toLowerCase();
                for (String type : VALID_WORLD_TYPES) {
                    if (type.startsWith(input)) {
                        completions.add(type);
                    }
                }
            } else if (subCmd.equals("unlock") || subCmd.equals("lock")) {
                String input = args[2].toLowerCase();
                if ("nether".startsWith(input)) completions.add("nether");
                if ("end".startsWith(input)) completions.add("end");
            } else if (subCmd.equals("invite") || subCmd.equals("uninvite")) {
                // /homeland invite/uninvite <名称> <玩家>
                String input = args[2].toLowerCase();
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (p.getName().toLowerCase().startsWith(input)) {
                        completions.add(p.getName());
                    }
                }
            } else if (subCmd.equals("home") && sender.hasPermission(PERM_ADMIN)) {
                // /homeland home <玩家> <家园名称> — 仅补全在线玩家的家园
                String input = args[2].toLowerCase();
                Player targetPlayer = plugin.getServer().getPlayer(args[1]);
                if (targetPlayer != null) {
                    for (Homeland h : plugin.getHomelandManager().getHomelands(targetPlayer.getUniqueId())) {
                        if (h.getName().toLowerCase().startsWith(input)) {
                            completions.add(h.getName());
                        }
                    }
                }
            } else if (subCmd.equals("admin")) {
                String adminSub = args[1].toLowerCase();
                if (sender.hasPermission(PERM_ADMIN)) {
                    switch (adminSub) {
                        case "create", "delete", "tp", "border", "unlock" -> {
                            String input = args[2].toLowerCase();
                            for (Player player : plugin.getServer().getOnlinePlayers()) {
                                if (player.getName().toLowerCase().startsWith(input)) {
                                    completions.add(player.getName());
                                }
                            }
                        }
                    }
                }
            }
        } else if (args.length == 4) {
            String subCmd = args[0].toLowerCase();

            if (subCmd.equals("admin")) {
                String adminSub = args[1].toLowerCase();
                if (sender.hasPermission(PERM_ADMIN)) {
                    String input = args[3].toLowerCase();

                    switch (adminSub) {
                        case "delete", "tp", "border", "unlock", "lock" -> {
                            // /homeland admin <子命令> <玩家> <家园名称> — 仅补全在线玩家
                            Player targetPlayer = plugin.getServer().getPlayer(args[2]);
                            if (targetPlayer != null) {
                                for (Homeland h : plugin.getHomelandManager().getHomelands(targetPlayer.getUniqueId())) {
                                    if (h.getName().toLowerCase().startsWith(input)) {
                                        completions.add(h.getName());
                                    }
                                }
                            }
                        }
                        case "create" -> {
                            // 创建时没有现有家园名可补全，让玩家自己输入
                        }
                    }
                }
            }
        } else if (args.length == 5) {
            String subCmd = args[0].toLowerCase();

            if (subCmd.equals("admin")) {
                String adminSub = args[1].toLowerCase();
                if (sender.hasPermission(PERM_ADMIN)) {
                    String input = args[4].toLowerCase();

                    if (adminSub.equals("border")) {
                        // /homeland admin border <玩家> <名称> expand
                        if ("expand".startsWith(input)) {
                            completions.add("expand");
                        }
                    } else if (adminSub.equals("unlock") || adminSub.equals("lock")) {
                        // /homeland admin unlock <玩家> <名称> <nether|end>
                        if ("nether".startsWith(input)) completions.add("nether");
                        if ("end".startsWith(input)) completions.add("end");
                    } else if (adminSub.equals("create")) {
                        // /homeland admin create <玩家> <名称> [default|void|flat]
                        for (String type : VALID_WORLD_TYPES) {
                            if (type.startsWith(input)) {
                                completions.add(type);
                            }
                        }
                    }
                }
            }
        }

        return completions;
    }
}