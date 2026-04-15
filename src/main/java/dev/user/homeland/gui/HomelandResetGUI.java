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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 重置世界 GUI：主世界/下界/末地
 */
public class HomelandResetGUI extends AbstractGUI {

    private final String homelandName;
    private final GUIConfig.Reset cfg;
    private final UUID targetUuid;
    private final String targetName;
    private final boolean isAdmin;
    private String pendingResetDimension = null;

    private HomelandResetGUI(SimpleHomelandPlugin plugin, Player player, String homelandName) {
        super(plugin, player, plugin.getConfigManager().getMessage("gui.reset-title", "name", homelandName),
                plugin.getConfigManager().getGUIConfig().getReset().getSize());
        this.homelandName = homelandName;
        this.cfg = plugin.getConfigManager().getGUIConfig().getReset();
        this.targetUuid = player.getUniqueId();
        this.targetName = null;
        this.isAdmin = false;
    }

    private HomelandResetGUI(SimpleHomelandPlugin plugin, Player admin, UUID targetUuid, String targetName, String homelandName) {
        super(plugin, admin, plugin.getConfigManager().getMessage("gui.reset-title", "name", homelandName),
                plugin.getConfigManager().getGUIConfig().getReset().getSize());
        this.homelandName = homelandName;
        this.cfg = plugin.getConfigManager().getGUIConfig().getReset();
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.isAdmin = true;
    }

    public static void open(SimpleHomelandPlugin plugin, Player player, String homelandName) {
        new HomelandResetGUI(plugin, player, homelandName).open();
    }

    public static void openAdmin(SimpleHomelandPlugin plugin, Player admin, UUID targetUuid, String targetName, String homelandName) {
        new HomelandResetGUI(plugin, admin, targetUuid, targetName, homelandName).open();
    }

    @Override
    public void initialize() {
        actions.clear();

        Optional<Homeland> optHomeland = plugin.getHomelandManager().getHomeland(targetUuid, homelandName);
        if (optHomeland.isEmpty()) {
            player.sendMessage(MessageUtil.toComponent(plugin.getConfigManager().getMessage("homeland-not-found", "name", homelandName)));
            abort();
            return;
        }

        Homeland homeland = optHomeland.get();
        ConfigManager config = plugin.getConfigManager();

        ItemStack border = createBorderItem();
        fillBorder(border);

        // 重置主世界（始终可用）
        setItem(cfg.getOverworldSlot(), createOverworldItem(config), (p, e) -> {
            close();
            HomelandResetTypeGUI.open(plugin, p, targetUuid, targetName, homelandName, isAdmin);
        });

        // 重置下界
        if (homeland.hasNether()) {
            if ("nether".equals(pendingResetDimension)) {
                setItem(cfg.getNetherSlot(), createConfirmItem(config, "下界"), (p, e) -> {
                    pendingResetDimension = null;
                    if (isAdmin) {
                        plugin.getHomelandManager().resetDimensionAdmin(p, targetUuid, homelandName, "nether",
                                () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L),
                                () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L));
                    } else {
                        plugin.getHomelandManager().resetDimension(p, homelandName, "nether",
                                () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L),
                                () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L));
                    }
                });
            } else {
                setItem(cfg.getNetherSlot(), createNetherItem(config), (p, e) -> {
                    pendingResetDimension = "nether";
                    refresh();
                });
            }
        } else {
            setItem(cfg.getNetherSlot(), createLockedItem(config, "下界"));
        }

        // 重置末地
        if (homeland.hasEnd()) {
            if ("end".equals(pendingResetDimension)) {
                setItem(cfg.getEndSlot(), createConfirmItem(config, "末地"), (p, e) -> {
                    pendingResetDimension = null;
                    if (isAdmin) {
                        plugin.getHomelandManager().resetDimensionAdmin(p, targetUuid, homelandName, "end",
                                () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L),
                                () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L));
                    } else {
                        plugin.getHomelandManager().resetDimension(p, homelandName, "end",
                                () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L),
                                () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L));
                    }
                });
            } else {
                setItem(cfg.getEndSlot(), createEndItem(config), (p, e) -> {
                    pendingResetDimension = "end";
                    refresh();
                });
            }
        } else {
            setItem(cfg.getEndSlot(), createLockedItem(config, "末地"));
        }

        // 关闭按钮
        setItem(cfg.getCloseSlot(), createCloseItem(), (p, e) -> close());
    }

    private ItemStack createOverworldItem(ConfigManager config) {
        ItemStack item = new ItemStack(cfg.getOverworldMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(config.getMessage("gui.reset-overworld-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        addCostLore(lore, config, config.getResetOverworldMoney(), config.getResetOverworldPoints());
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.reset-overworld-lore")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createNetherItem(ConfigManager config) {
        ItemStack item = new ItemStack(cfg.getNetherMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(config.getMessage("gui.reset-nether-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        addCostLore(lore, config, config.getResetNetherMoney(), config.getResetNetherPoints());
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.reset-nether-lore")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createEndItem(ConfigManager config) {
        ItemStack item = new ItemStack(cfg.getEndMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(config.getMessage("gui.reset-end-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        addCostLore(lore, config, config.getResetEndMoney(), config.getResetEndPoints());
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.reset-end-lore")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createLockedItem(ConfigManager config, String dimension) {
        ItemStack item = new ItemStack(cfg.getLockedMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(config.getMessage(
                "nether".equals(dimension) ? "gui.reset-nether-name" : "gui.reset-end-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.reset-locked-lore")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createConfirmItem(ConfigManager config, String dimension) {
        ItemStack item = new ItemStack(cfg.getConfirmMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(config.getMessage("gui.reset-confirm-name", "dimension", dimension)));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.reset-confirm-click")));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.reset-confirm-cancel")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void addCostLore(List<Component> lore, ConfigManager config, double money, int points) {
        if (isAdmin) return;
        if (money > 0) {
            lore.add(MessageUtil.guiLore(config.getMessage("gui.reset-cost-money", "money", String.format("%.0f", money))));
        }
        if (points > 0) {
            lore.add(MessageUtil.guiLore(config.getMessage("gui.reset-cost-points", "points", String.valueOf(points))));
        }
        if (money == 0 && points == 0) {
            lore.add(MessageUtil.guiLore(config.getMessage("gui.reset-cost-free")));
        }
    }
}
