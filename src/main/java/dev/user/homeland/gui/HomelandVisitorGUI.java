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
 * 在线玩家访问 GUI
 */
public class HomelandVisitorGUI extends AbstractGUI {

    private final GUIConfig.Visitor cfg;

    public HomelandVisitorGUI(SimpleHomelandPlugin plugin, Player player) {
        super(plugin, player, plugin.getConfigManager().getMessage("gui.visitor-title"),
                plugin.getConfigManager().getGUIConfig().getVisitor().getSize());
        this.cfg = plugin.getConfigManager().getGUIConfig().getVisitor();
    }

    public static void open(SimpleHomelandPlugin plugin, Player player) {
        new HomelandVisitorGUI(plugin, player).open();
    }

    @Override
    public void initialize() {
        actions.clear();

        ItemStack border = createBorderItem();
        fillBorder(border);

        // 提示信息
        setItem(cfg.getInfoSlot(), createInfoItem());

        // 在线玩家列表（排除自己）
        List<Player> onlinePlayers = new ArrayList<>(Arrays.asList(
                plugin.getServer().getOnlinePlayers().toArray(new Player[0])));
        onlinePlayers.removeIf(p -> p.getUniqueId().equals(player.getUniqueId()));

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
                    HomelandVisitorPlayerGUI.open(plugin, p, target.getUniqueId(), target.getName());
                });
            }

            addPageButtons(onlinePlayers.size(), cfg.getItemsPerPage(), cfg.getPagePrevSlot(), cfg.getPageNextSlot());
        }

        // 返回按钮
        setItem(cfg.getBackSlot(), createBackItem(
                plugin.getConfigManager().getGUIConfig().getGlobal().getBackMaterial(),
                "gui.visitor-back-players"), (p, e) -> {
            close();
            HomelandGUI.open(plugin, p);
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
        meta.displayName(MessageUtil.guiName(config.getMessage("gui.visitor-player-name", "name", target.getName())));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));

        List<Homeland> homelands = plugin.getHomelandManager().getHomelands(target.getUniqueId());
        lore.add(MessageUtil.guiLore(config.getMessage("gui.visitor-player-homeland-count", "count", String.valueOf(homelands.size()))));

        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.visitor-player-click")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createInfoItem() {
        ItemStack item = new ItemStack(cfg.getInfoMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        ConfigManager config = plugin.getConfigManager();
        meta.displayName(MessageUtil.guiName(config.getMessage("gui.visitor-info-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.visitor-info-lore")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createEmptyItem() {
        ItemStack item = new ItemStack(cfg.getEmptyMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(plugin.getConfigManager().getMessage("gui.visitor-empty-players")));

        item.setItemMeta(meta);
        return item;
    }

    /**
     * 玩家家园列表（访客视角）
     */
    public static class HomelandVisitorPlayerGUI extends AbstractGUI {

        private final UUID targetUuid;
        private final String targetName;
        private volatile List<Homeland> loadedHomelands;
        private final GUIConfig.Visitor.PlayerHomelands cfg;

        public HomelandVisitorPlayerGUI(SimpleHomelandPlugin plugin, Player viewer,
                                         UUID targetUuid, String targetName, int page) {
            super(plugin, viewer,
                    plugin.getConfigManager().getMessage("gui.visitor-player-title", "player", targetName),
                    plugin.getConfigManager().getGUIConfig().getVisitor().getPlayerHomelands().getSize());
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.currentPage = page;
            this.cfg = plugin.getConfigManager().getGUIConfig().getVisitor().getPlayerHomelands();
        }

        public static void open(SimpleHomelandPlugin plugin, Player viewer,
                                UUID targetUuid, String targetName) {
            HomelandVisitorPlayerGUI gui = new HomelandVisitorPlayerGUI(plugin, viewer, targetUuid, targetName, 0);
            List<Homeland> cached = plugin.getHomelandManager().getHomelands(targetUuid);
            if (plugin.getHomelandManager().isHomelandsCached(targetUuid)) {
                gui.loadedHomelands = cached;
                gui.open();
                return;
            }
            plugin.getHomelandManager().getOrLoadHomelandsAsync(targetUuid, homelands -> {
                viewer.getScheduler().execute(plugin, () -> {
                    if (!GUIManager.hasOpenGUI(viewer.getUniqueId())) return;
                    gui.loadedHomelands = homelands;
                    gui.open();
                }, () -> {}, 0L);
            });
        }

        @Override
        public void initialize() {
            actions.clear();

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
                    boolean canAccess = plugin.getHomelandManager().canEnterWorld(player, h.getWorldKey())
                            && player.hasPermission("simplehomeland.homeland.home");
                    if (canAccess) {
                        setItem(pageSlots[i], createHomelandItem(h, true), (p, e) -> {
                            close();
                            plugin.getHomelandManager().teleportToHomeland(p, targetUuid, h.getName());
                        });
                    } else {
                        setItem(pageSlots[i], createHomelandItem(h, false), null);
                    }
                }

                addPageButtons(homelands.size(), cfg.getItemsPerPage(), cfg.getPagePrevSlot(), cfg.getPageNextSlot());
            }

            // 返回按钮
            setItem(cfg.getBackSlot(), createBackItem(
                    plugin.getConfigManager().getGUIConfig().getGlobal().getBackMaterial(),
                    "gui.visitor-back-players"), (p, e) -> {
                close();
                HomelandVisitorGUI.open(plugin, p);
            });

            // 关闭按钮
            setItem(cfg.getCloseSlot(), createCloseItem(), (p, e) -> close());
        }

        private ItemStack createHomelandItem(Homeland h, boolean canAccess) {
            Material mat = canAccess ? cfg.getHomelandAccessibleMaterial() : cfg.getHomelandDeniedMaterial();
            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return item;

            ConfigManager config = plugin.getConfigManager();
            meta.displayName(MessageUtil.guiName(config.getMessage("gui.visitor-homeland-name", "name", h.getName())));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
            lore.add(MessageUtil.guiLore(config.getMessage("gui.visitor-homeland-border", "border", String.valueOf(h.getBorderRadius()))));
            lore.add(MessageUtil.guiLore(config.getMessage(h.isPublic() ? "gui.visitor-homeland-public" : "gui.visitor-homeland-private")));
            lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
            lore.add(MessageUtil.guiLore(config.getMessage(canAccess ? "gui.visitor-homeland-access-allowed" : "gui.visitor-homeland-access-denied")));
            lore.add(MessageUtil.guiLore(config.getMessage(canAccess ? "gui.visitor-homeland-click-teleport" : "gui.visitor-homeland-click-denied")));

            meta.lore(lore);
            item.setItemMeta(meta);
            return item;
        }

        private ItemStack createEmptyItem() {
            ItemStack item = new ItemStack(cfg.getEmptyMaterial());
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return item;

            meta.displayName(MessageUtil.guiName(plugin.getConfigManager().getMessage("gui.visitor-empty-homelands", "name", targetName)));

            item.setItemMeta(meta);
            return item;
        }

    }
}
