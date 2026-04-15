package dev.user.homeland.gui;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.config.ConfigManager;
import dev.user.homeland.config.GUIConfig;
import dev.user.homeland.config.PriceTier;
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
import java.util.List;
import java.util.UUID;

/**
 * 玩家家园列表 GUI
 */
public class HomelandGUI extends AbstractGUI {

    private final GUIConfig.HomelandList cfg;

    public HomelandGUI(SimpleHomelandPlugin plugin, Player player) {
        super(plugin, player, plugin.getConfigManager().getMessage("gui.homeland-title"),
                plugin.getConfigManager().getGUIConfig().getHomelandList().getSize());
        this.cfg = plugin.getConfigManager().getGUIConfig().getHomelandList();
    }

    public static void open(SimpleHomelandPlugin plugin, Player player) {
        new HomelandGUI(plugin, player).open();
    }

    @Override
    public void initialize() {
        actions.clear();

        ItemStack border = createBorderItem();
        fillBorder(border);

        UUID playerUuid = player.getUniqueId();
        List<Homeland> ownHomelands = plugin.getHomelandManager().getHomelands(playerUuid);
        List<Homeland> invitedHomelands = plugin.getHomelandManager().getInvitedHomelands(playerUuid);
        ConfigManager config = plugin.getConfigManager();

        // 合并列表：自己的家园 + 被邀请的家园
        List<Homeland> allHomelands = new ArrayList<>(ownHomelands);
        allHomelands.addAll(invitedHomelands);

        if (allHomelands.isEmpty()) {
            setItem(cfg.getEmptySlot(), createEmptyItem());
        } else {
            int[] pageSlots = cfg.getPageSlots();
            int start = currentPage * cfg.getItemsPerPage();
            for (int i = 0; i < cfg.getItemsPerPage() && i < pageSlots.length; i++) {
                int idx = start + i;
                if (idx >= allHomelands.size()) break;
                Homeland h = allHomelands.get(idx);
                boolean isOwn = h.getOwnerUuid().equals(playerUuid);
                if (isOwn) {
                    setItem(pageSlots[i], createHomelandItem(h), (p, e) -> {
                        HomelandManageGUI.open(plugin, p, h.getName());
                    });
                } else {
                    setItem(pageSlots[i], createInvitedHomelandItem(h), (p, e) -> {
                        close();
                        plugin.getHomelandManager().teleportToHomeland(p, h.getOwnerUuid(), h.getName());
                    });
                }
            }

            addPageButtons(allHomelands.size(), cfg.getItemsPerPage(), cfg.getPagePrevSlot(), cfg.getPageNextSlot());
        }

        // 创建按钮（如果未达上限且有权限）
        if (ownHomelands.size() < config.getMaxHomelands()
                && player.hasPermission("simplehomeland.homeland.create")) {
            setItem(cfg.getCreateSlot(), createCreateItem(), (p, e) -> {
                close();
                plugin.getHomelandManager().startNaming(p.getUniqueId());
                MessageUtil.send(p, config.getMessage("gui.create-enter-name"));
            });
        }

        // 返回主世界按钮
        setItem(cfg.getReturnToMainSlot(), createReturnToMainItem(), (p, e) -> {
            close();
            plugin.getHomelandManager().teleportToMainWorld(p);
        });

        // 关闭按钮
        setItem(cfg.getCloseSlot(), createCloseItem(), (p, e) -> close());

        // 在线玩家按钮
        setItem(cfg.getOnlinePlayersSlot(), createOnlinePlayersItem(), (p, e) -> {
            close();
            HomelandVisitorGUI.open(plugin, p);
        });
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
        lore.add(MessageUtil.guiLore(config.getMessage(h.isPublic() ? "gui.homeland-item-public" : "gui.homeland-item-private")));
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.homeland-item-click-manage")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @SuppressWarnings("deprecation")
    private ItemStack createInvitedHomelandItem(Homeland h) {
        ItemStack item = new ItemStack(cfg.getInvitedMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        ConfigManager config = plugin.getConfigManager();
        String ownerName = plugin.getServer().getOfflinePlayer(h.getOwnerUuid()).getName();
        if (ownerName == null) ownerName = h.getOwnerUuid().toString().substring(0, 8);

        meta.displayName(MessageUtil.guiName(config.getMessage("gui.invited-homeland-item-name",
                "owner", ownerName, "name", h.getName())));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.invited-homeland-item-lore")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createEmptyItem() {
        ItemStack item = new ItemStack(cfg.getEmptyMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        ConfigManager config = plugin.getConfigManager();
        meta.displayName(MessageUtil.guiName(config.getMessage("gui.empty-homeland-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.empty-homeland-lore")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createCreateItem() {
        ItemStack item = new ItemStack(cfg.getCreateMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        ConfigManager config = plugin.getConfigManager();
        meta.displayName(MessageUtil.guiName(config.getMessage("gui.create-item-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));

        PriceTier tier = config.getCreationCost(
                plugin.getHomelandManager().getHomelandCount(player.getUniqueId()));
        if (tier.getMoney() > 0) {
            lore.add(MessageUtil.guiLore(config.getMessage("gui.create-item-money", "money", String.format("%.0f", tier.getMoney()))));
        }
        if (tier.getPoints() > 0) {
            lore.add(MessageUtil.guiLore(config.getMessage("gui.create-item-points", "points", String.valueOf(tier.getPoints()))));
        }
        if (tier.getMoney() == 0 && tier.getPoints() == 0) {
            lore.add(MessageUtil.guiLore(config.getMessage("gui.create-item-free")));
        }

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createReturnToMainItem() {
        ItemStack item = new ItemStack(cfg.getReturnToMainMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(plugin.getConfigManager().getMessage("gui.return-to-main-item-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(plugin.getConfigManager().getMessage("gui.return-to-main-item-lore")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @SuppressWarnings("deprecation")
    private ItemStack createOnlinePlayersItem() {
        ItemStack item = new ItemStack(cfg.getOnlinePlayersMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (item.getType() == Material.PLAYER_HEAD && meta instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(player);
        }

        ConfigManager config = plugin.getConfigManager();
        meta.displayName(MessageUtil.guiName(config.getMessage("gui.online-players-item-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.online-players-item-lore")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
