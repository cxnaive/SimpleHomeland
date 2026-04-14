package dev.user.homeland.gui;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.config.ConfigManager;
import dev.user.homeland.config.GUIConfig;
import dev.user.homeland.model.Homeland;
import dev.user.homeland.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 管理员家园管理 GUI
 */
public class HomelandAdminGUI extends AbstractGUI {

    private final GUIConfig.Admin cfg;

    public HomelandAdminGUI(SimpleHomelandPlugin plugin, Player player) {
        super(plugin, player, plugin.getConfigManager().getMessage("gui.admin-title"),
                plugin.getConfigManager().getGUIConfig().getAdmin().getSize());
        this.cfg = plugin.getConfigManager().getGUIConfig().getAdmin();
    }

    public static void open(SimpleHomelandPlugin plugin, Player player) {
        new HomelandAdminGUI(plugin, player).open();
    }

    @Override
    public void initialize() {
        actions.clear();

        if (!player.hasPermission("simplehomeland.admin")) {
            abort();
            return;
        }

        ItemStack border = createBorderItem();
        fillBorder(border);

        // 提示信息
        setItem(cfg.getInfoSlot(), createInfoItem());

        // 显示在线玩家
        List<Player> onlinePlayers = new ArrayList<>(Arrays.asList(
                plugin.getServer().getOnlinePlayers().toArray(new Player[0])));

        if (onlinePlayers.isEmpty()) {
            setItem(cfg.getEmptySlot(), createEmptyItem());
        } else {
            int[] pageSlots = cfg.getPageSlots();
            int start = currentPage * cfg.getItemsPerPage();
            for (int i = 0; i < cfg.getItemsPerPage() && i < pageSlots.length; i++) {
                int idx = start + i;
                if (idx >= onlinePlayers.size()) break;
                Player target = onlinePlayers.get(idx);
                setItem(pageSlots[i], createPlayerItem(target), (p, e) -> {
                    close();
                    HomelandAdminPlayerGUI.open(plugin, p, target.getUniqueId(), target.getName());
                });
            }

            addPageButtons(onlinePlayers.size(), cfg.getItemsPerPage(), cfg.getPagePrevSlot(), cfg.getPageNextSlot());
        }

        // 大厅访客权限按钮
        setItem(cfg.getLobbyFlagSlot(), createLobbyFlagItem(), (p, e) -> {
            close();
            HomelandVisitorFlagGUI.openForLobby(plugin, p);
        });

        // 关闭按钮
        setItem(cfg.getCloseSlot(), createCloseItem(), (p, e) -> close());
    }

    @SuppressWarnings("deprecation")
    private ItemStack createPlayerItem(Player target) {
        ItemStack item = new ItemStack(cfg.getPlayerMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (meta instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(target);
        }

        ConfigManager config = plugin.getConfigManager();
        meta.displayName(MessageUtil.guiName(config.getMessage("gui.admin-player-name", "name", target.getName())));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));

        List<Homeland> homelands = plugin.getHomelandManager().getHomelands(target.getUniqueId());
        lore.add(MessageUtil.guiLore(config.getMessage("gui.admin-player-homeland-count", "count", String.valueOf(homelands.size()))));

        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.admin-player-click-manage")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createInfoItem() {
        ItemStack item = new ItemStack(cfg.getInfoMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        ConfigManager config = plugin.getConfigManager();
        meta.displayName(MessageUtil.guiName(config.getMessage("gui.admin-select-player")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.admin-select-player-lore")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createEmptyItem() {
        ItemStack item = new ItemStack(cfg.getEmptyMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(plugin.getConfigManager().getMessage("gui.admin-empty-players")));

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createLobbyFlagItem() {
        ItemStack item = new ItemStack(cfg.getLobbyFlagMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        ConfigManager config = plugin.getConfigManager();
        meta.displayName(MessageUtil.guiName(config.getMessage("gui.lobby-flag-item-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.lobby-flag-item-lore")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * 管理员查看指定玩家家园列表的子GUI
     */
    public static class HomelandAdminPlayerGUI extends AbstractGUI {

        private final UUID targetUuid;
        private final String targetName;
        private volatile List<Homeland> loadedHomelands;
        private final GUIConfig.Admin.PlayerHomelands cfg;

        public HomelandAdminPlayerGUI(SimpleHomelandPlugin plugin, Player admin, UUID targetUuid, String targetName, int page) {
            super(plugin, admin, plugin.getConfigManager().getMessage("gui.admin-player-title", "player", targetName),
                    plugin.getConfigManager().getGUIConfig().getAdmin().getPlayerHomelands().getSize());
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.currentPage = page;
            this.cfg = plugin.getConfigManager().getGUIConfig().getAdmin().getPlayerHomelands();
        }

        public static void open(SimpleHomelandPlugin plugin, Player admin, UUID targetUuid, String targetName) {
            HomelandAdminPlayerGUI gui = new HomelandAdminPlayerGUI(plugin, admin, targetUuid, targetName, 0);
            List<Homeland> cached = plugin.getHomelandManager().getHomelands(targetUuid);
            if (plugin.getHomelandManager().isHomelandsCached(targetUuid)) {
                gui.loadedHomelands = cached;
                gui.open();
                return;
            }
            plugin.getHomelandManager().getOrLoadHomelandsAsync(targetUuid, homelands -> {
                admin.getScheduler().execute(plugin, () -> {
                    if (!GUIManager.hasOpenGUI(admin.getUniqueId())) return;
                    gui.loadedHomelands = homelands;
                    gui.open();
                }, () -> {}, 0L);
            });
        }

        @Override
        public void initialize() {
            actions.clear();

            if (!player.hasPermission("simplehomeland.admin")) {
                abort();
                return;
            }

            ItemStack border = createBorderItem();
            fillBorder(border);

            List<Homeland> homelands = loadedHomelands != null ? loadedHomelands : plugin.getHomelandManager().getHomelands(targetUuid);
            ConfigManager config = plugin.getConfigManager();

            if (homelands.isEmpty()) {
                setItem(cfg.getEmptySlot(), createEmptyItem());
            } else {
                int[] pageSlots = cfg.getPageSlots();
                int start = currentPage * cfg.getItemsPerPage();
                for (int i = 0; i < cfg.getItemsPerPage() && i < pageSlots.length; i++) {
                    int idx = start + i;
                    if (idx >= homelands.size()) break;
                    Homeland h = homelands.get(idx);
                    setItem(pageSlots[i], createHomelandItem(h), (p, e) -> {
                        close();
                        HomelandManageGUI.openAdmin(plugin, p, targetUuid, targetName, h.getName());
                    });
                }

                addPageButtons(homelands.size(), cfg.getItemsPerPage(), cfg.getPagePrevSlot(), cfg.getPageNextSlot());
            }

            // 创建家园按钮
            if (homelands.size() < config.getMaxHomelands()) {
                setItem(cfg.getCreateSlot(), createCreateItem(), (p, e) -> {
                    close();
                    plugin.getHomelandManager().startAdminNaming(p.getUniqueId(), targetUuid, targetName);
                    MessageUtil.send(p, config.getMessage("gui.create-enter-name"));
                });
            }

            // 返回按钮
            setItem(cfg.getBackSlot(), createBackItem(
                    plugin.getConfigManager().getGUIConfig().getGlobal().getBackMaterial(),
                    "gui.admin-back-players"), (p, e) -> {
                close();
                HomelandAdminGUI.open(plugin, p);
            });

            // 关闭按钮
            setItem(cfg.getCloseSlot(), createCloseItem(), (p, e) -> close());
        }

        private ItemStack createHomelandItem(Homeland h) {
            ItemStack item = new ItemStack(cfg.getHomelandMaterial());
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return item;

            ConfigManager config = plugin.getConfigManager();
            meta.displayName(MessageUtil.guiName(config.getMessage("gui.homeland-item-name", "name", h.getName())));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
            lore.add(MessageUtil.guiLore(config.getMessage("gui.homeland-item-border", "border", String.valueOf(h.getBorderRadius()))));
            lore.add(MessageUtil.guiLore(config.getMessage(h.hasNether() ? "gui.homeland-item-nether-unlocked" : "gui.homeland-item-nether-locked")));
            lore.add(MessageUtil.guiLore(config.getMessage(h.hasEnd() ? "gui.homeland-item-end-unlocked" : "gui.homeland-item-end-locked")));
            lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
            lore.add(MessageUtil.guiLore(config.getMessage("gui.homeland-item-click-manage")));

            meta.lore(lore);
            item.setItemMeta(meta);
            return item;
        }

        private ItemStack createEmptyItem() {
            ItemStack item = new ItemStack(cfg.getEmptyMaterial());
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return item;

            meta.displayName(MessageUtil.guiName(plugin.getConfigManager().getMessage("gui.admin-empty-homelands", "name", targetName)));

            item.setItemMeta(meta);
            return item;
        }

        private ItemStack createCreateItem() {
            ItemStack item = new ItemStack(cfg.getCreateMaterial());
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return item;

            meta.displayName(MessageUtil.guiName(plugin.getConfigManager().getMessage("gui.admin-create-for-player")));

            item.setItemMeta(meta);
            return item;
        }

    }
}
