package dev.user.homeland.gui;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.config.GUIConfig;
import dev.user.homeland.model.Homeland;
import dev.user.homeland.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

/**
 * 邀请管理 GUI（双模式分页）
 * 模式 A：在线玩家 → 点击邀请
 * 模式 B：已邀请玩家 → 点击取消邀请
 */
public class HomelandInviteManageGUI extends AbstractGUI {

    private final String homelandName;
    private final UUID targetUuid;
    private final String targetName;
    private final boolean isAdmin;
    private final GUIConfig.InviteManage cfg;
    private boolean showInvited = false;
    private Map<UUID, String> invitedPlayers;

    // 玩家模式构造
    public HomelandInviteManageGUI(SimpleHomelandPlugin plugin, Player player, String homelandName) {
        super(plugin, player, plugin.getConfigManager().getMessage("gui.invite-manage-title", "name", homelandName),
                plugin.getConfigManager().getGUIConfig().getInviteManage().getSize());
        this.homelandName = homelandName;
        this.targetUuid = player.getUniqueId();
        this.targetName = null;
        this.isAdmin = false;
        this.cfg = plugin.getConfigManager().getGUIConfig().getInviteManage();
    }

    // admin 模式构造
    private HomelandInviteManageGUI(SimpleHomelandPlugin plugin, Player admin, UUID targetUuid, String targetName, String homelandName) {
        super(plugin, admin, plugin.getConfigManager().getMessage("gui.invite-manage-title", "name", homelandName),
                plugin.getConfigManager().getGUIConfig().getInviteManage().getSize());
        this.homelandName = homelandName;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.isAdmin = true;
        this.cfg = plugin.getConfigManager().getGUIConfig().getInviteManage();
    }

    public static void open(SimpleHomelandPlugin plugin, Player player, String homelandName) {
        HomelandInviteManageGUI gui = new HomelandInviteManageGUI(plugin, player, homelandName);

        Optional<Homeland> optHomeland = plugin.getHomelandManager().getHomeland(player.getUniqueId(), homelandName);
        if (optHomeland.isEmpty()) {
            gui.open(); // will abort
            return;
        }

        // 先加载已邀请列表，再打开 GUI
        plugin.getHomelandManager().getInvitedPlayers(optHomeland.get().getId(), map -> {
            player.getScheduler().execute(plugin, () -> {
                gui.invitedPlayers = map;
                gui.open();
            }, () -> {}, 0L);
        });
    }

    public static void openAdmin(SimpleHomelandPlugin plugin, Player admin, UUID ownerUuid, String ownerName, String homelandName) {
        HomelandInviteManageGUI gui = new HomelandInviteManageGUI(plugin, admin, ownerUuid, ownerName, homelandName);

        Optional<Homeland> optHomeland = plugin.getHomelandManager().getHomeland(ownerUuid, homelandName);
        if (optHomeland.isEmpty()) {
            gui.open(); // will abort
            return;
        }

        plugin.getHomelandManager().getInvitedPlayers(optHomeland.get().getId(), map -> {
            admin.getScheduler().execute(plugin, () -> {
                gui.invitedPlayers = map;
                gui.open();
            }, () -> {}, 0L);
        });
    }

    @Override
    public void initialize() {
        actions.clear();

        Optional<Homeland> optHomeland = plugin.getHomelandManager().getHomeland(targetUuid, homelandName);
        if (optHomeland.isEmpty()) {
            abort();
            return;
        }

        Homeland homeland = optHomeland.get();

        ItemStack border = createBorderItem();
        fillBorder(border);

        // 模式切换按钮
        setItem(cfg.getModeSlot(), createModeItem(), (p, e) -> {
            showInvited = !showInvited;
            currentPage = 0;
            if (showInvited) {
                // 切换到已邀请模式时重新加载列表
                loadInvitedPlayers(homeland.getId());
            } else {
                refresh();
            }
        });

        if (showInvited) {
            populateInvitedMode(homeland);
        } else {
            populateOnlineMode(homeland);
        }

        // 翻页按钮 + 页码信息
        int totalItems = showInvited
                ? (invitedPlayers != null ? invitedPlayers.size() : 0)
                : countAvailableOnlinePlayers(homeland);
        addPageButtons(totalItems, cfg.getItemsPerPage(), cfg.getPrevSlot(), cfg.getNextSlot());

        // 页码信息
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / cfg.getItemsPerPage()));
        setItem(cfg.getPageInfoSlot(), createPageInfoItem(currentPage + 1, totalPages), null);

        // 返回按钮
        if (isAdmin) {
            setItem(cfg.getBackSlot(), createBackItem(
                    plugin.getConfigManager().getGUIConfig().getGlobal().getBackMaterial(),
                    "gui.admin-back-player-homelands"), (p, e) -> {
                close();
                HomelandManageGUI.openAdmin(plugin, p, targetUuid, targetName, homelandName);
            });
        } else {
            setItem(cfg.getBackSlot(), createBackItem(
                    plugin.getConfigManager().getGUIConfig().getGlobal().getBackMaterial(),
                    "gui.back-item-name"), (p, e) -> {
                close();
                HomelandManageGUI.open(plugin, p, homelandName);
            });
        }

        // 关闭按钮
        setItem(cfg.getCloseSlot(), createCloseItem(), (p, e) -> close());
    }

    // ==================== 在线玩家模式 ====================

    private int countAvailableOnlinePlayers(Homeland homeland) {
        int count = 0;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.getUniqueId().equals(homeland.getOwnerUuid())) continue;
            if (invitedPlayers != null && invitedPlayers.containsKey(p.getUniqueId())) continue;
            count++;
        }
        return count;
    }

    private void populateOnlineMode(Homeland homeland) {
        List<Player> available = new ArrayList<>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.getUniqueId().equals(homeland.getOwnerUuid())) continue;
            if (invitedPlayers != null && invitedPlayers.containsKey(p.getUniqueId())) continue;
            available.add(p);
        }

        if (available.isEmpty()) {
            setItem(cfg.getEmptySlot(), createEmptyItem(
                    plugin.getConfigManager().getMessage("gui.invite-manage-empty-online")), null);
            return;
        }

        int[] pageSlots = cfg.getPageSlots();
        int start = currentPage * cfg.getItemsPerPage();
        for (int i = 0; i < cfg.getItemsPerPage() && i < pageSlots.length; i++) {
            int idx = start + i;
            if (idx >= available.size()) break;
            Player target = available.get(idx);
            setItem(pageSlots[i], createOnlinePlayerItem(target), (p, e) -> {
                if (isAdmin) {
                    plugin.getHomelandManager().invitePlayerAdmin(p, targetUuid, homelandName,
                            target.getUniqueId(), target.getName(),
                            () -> {
                                if (invitedPlayers == null) invitedPlayers = new LinkedHashMap<>();
                                invitedPlayers.put(target.getUniqueId(), target.getName());
                                p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L);
                            },
                            () -> {});
                } else {
                    plugin.getHomelandManager().invitePlayer(p, homelandName,
                            target.getUniqueId(), target.getName(),
                            () -> {
                                if (invitedPlayers == null) invitedPlayers = new LinkedHashMap<>();
                                invitedPlayers.put(target.getUniqueId(), target.getName());
                                p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L);
                            },
                            () -> {});
                }
            });
        }
    }

    // ==================== 已邀请玩家模式 ====================

    private void populateInvitedMode(Homeland homeland) {
        if (invitedPlayers == null) {
            // 正在加载
            setItem(cfg.getEmptySlot(), createEmptyItem(
                    plugin.getConfigManager().getMessage("gui.invite-manage-loading")), null);
            return;
        }

        if (invitedPlayers.isEmpty()) {
            setItem(cfg.getEmptySlot(), createEmptyItem(
                    plugin.getConfigManager().getMessage("gui.invite-manage-empty-invited")), null);
            return;
        }

        List<Map.Entry<UUID, String>> entries = new ArrayList<>(invitedPlayers.entrySet());
        int[] pageSlots = cfg.getPageSlots();
        int start = currentPage * cfg.getItemsPerPage();
        for (int i = 0; i < cfg.getItemsPerPage() && i < pageSlots.length; i++) {
            int idx = start + i;
            if (idx >= entries.size()) break;
            Map.Entry<UUID, String> entry = entries.get(idx);
            UUID uuid = entry.getKey();
            String name = entry.getValue();
            setItem(pageSlots[i], createInvitedPlayerItem(uuid, name), (p, e) -> {
                if (isAdmin) {
                    plugin.getHomelandManager().uninvitePlayerAdmin(p, targetUuid, homelandName, uuid, name,
                            () -> {
                                invitedPlayers.remove(uuid);
                                p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L);
                            },
                            () -> {});
                } else {
                    plugin.getHomelandManager().uninvitePlayer(p, homelandName, uuid, name,
                            () -> {
                                invitedPlayers.remove(uuid);
                                p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L);
                            },
                            () -> {});
                }
            });
        }
    }

    private void loadInvitedPlayers(int homelandId) {
        plugin.getHomelandManager().getInvitedPlayers(homelandId, map -> {
            player.getScheduler().execute(plugin, () -> {
                invitedPlayers = map;
                refresh();
            }, () -> {}, 0L);
        });
    }

    // ==================== 物品创建 ====================

    @SuppressWarnings("deprecation")
    private ItemStack createOnlinePlayerItem(Player target) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (meta instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(target);
        }

        meta.displayName(MessageUtil.guiName(
                plugin.getConfigManager().getMessage("gui.invite-manage-player-name", "name", target.getName())));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(plugin.getConfigManager().getMessage("gui.invite-manage-click-invite")));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @SuppressWarnings("deprecation")
    private ItemStack createInvitedPlayerItem(UUID uuid, String name) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (meta instanceof SkullMeta skullMeta) {
            OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayer(uuid);
            skullMeta.setOwningPlayer(offlinePlayer);
        }

        meta.displayName(MessageUtil.guiName(
                plugin.getConfigManager().getMessage("gui.invite-manage-player-name", "name", name)));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(plugin.getConfigManager().getMessage("gui.invite-manage-click-uninvite")));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createModeItem() {
        Material mat = showInvited ? cfg.getModeInvitedMaterial() : cfg.getModeOnlineMaterial();
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String nameKey = showInvited ? "gui.invite-manage-mode-invited" : "gui.invite-manage-mode-online";
        String loreKey = showInvited ? "gui.invite-manage-mode-invited-lore" : "gui.invite-manage-mode-online-lore";

        meta.displayName(MessageUtil.guiName(plugin.getConfigManager().getMessage(nameKey)));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(plugin.getConfigManager().getMessage(loreKey)));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createEmptyItem(String message) {
        ItemStack item = new ItemStack(cfg.getEmptyMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(MessageUtil.guiName(message));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createPageInfoItem(int page, int total) {
        Material pageInfoMaterial = plugin.getConfigManager().getGUIConfig().getGlobal().getPageInfoMaterial();
        ItemStack item = new ItemStack(pageInfoMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(MessageUtil.guiName(
                plugin.getConfigManager().getMessage("gui.gamerule-page-info",
                        "page", String.valueOf(page),
                        "total", String.valueOf(total))));
        item.setItemMeta(meta);
        return item;
    }
}
