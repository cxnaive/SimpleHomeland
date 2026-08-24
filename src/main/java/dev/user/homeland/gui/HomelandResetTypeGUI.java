package dev.user.homeland.gui;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.config.ConfigManager;
import dev.user.homeland.config.GUIConfig;
import dev.user.homeland.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 重置主世界时选择地形类型
 */
public class HomelandResetTypeGUI extends AbstractGUI {

    private final String homelandName;
    private final GUIConfig.Create cfg;
    private final UUID targetUuid;
    private final boolean isAdmin;
    private String pendingWorldType = null;

    private HomelandResetTypeGUI(SimpleHomelandPlugin plugin, Player player, String homelandName,
                                  UUID targetUuid, boolean isAdmin) {
        super(plugin, player, plugin.getConfigManager().getMessage("gui.reset-type-title", "name", homelandName),
                plugin.getConfigManager().getGUIConfig().getCreate().getSize());
        this.homelandName = homelandName;
        this.cfg = plugin.getConfigManager().getGUIConfig().getCreate();
        this.targetUuid = targetUuid;
        this.isAdmin = isAdmin;
    }

    public static void open(SimpleHomelandPlugin plugin, Player player, UUID targetUuid, String targetName,
                            String homelandName, boolean isAdmin) {
        new HomelandResetTypeGUI(plugin, player, homelandName, targetUuid, isAdmin).open();
    }

    @Override
    public void initialize() {
        actions.clear();

        ItemStack border = createBorderItem();
        fillBorder(border);

        ConfigManager config = plugin.getConfigManager();

        setWorldTypeOption(cfg.getDefaultSlot(), "default", config.getMessage("gui.create-type-default-name"),
                createDefaultItem(config));
        setWorldTypeOption(cfg.getVoidSlot(), "void", config.getMessage("gui.create-type-void-name"),
                createVoidItem(config));
        setWorldTypeOption(cfg.getFlatSlot(), "flat", config.getMessage("gui.create-type-flat-name"),
                createFlatItem(config));

        // 关闭按钮
        setItem(cfg.getCloseSlot(), createCloseItem(), (p, e) -> close());
    }

    private void setWorldTypeOption(int slot, String worldType, String displayName, ItemStack normalItem) {
        if (worldType.equals(pendingWorldType)) {
            setItem(slot, createConfirmItem(displayName), (p, e) -> {
                pendingWorldType = null;
                close();
                doReset(p, worldType);
            });
            return;
        }

        setItem(slot, normalItem, (p, e) -> {
            pendingWorldType = worldType;
            refresh();
        });
    }

    @Override
    public GUIAction getAction(int slot) {
        GUIAction action = super.getAction(slot);
        int confirmSlot = getPendingConfirmSlot();
        if (confirmSlot < 0 || slot == confirmSlot) return action;

        return (p, e) -> {
            pendingWorldType = null;
            if (action != null) {
                action.execute(p, e);
            } else {
                refresh();
            }
        };
    }

    private int getPendingConfirmSlot() {
        if (pendingWorldType == null) return -1;
        return switch (pendingWorldType) {
            case "default" -> cfg.getDefaultSlot();
            case "void" -> cfg.getVoidSlot();
            case "flat" -> cfg.getFlatSlot();
            default -> -1;
        };
    }

    private void doReset(Player p, String worldType) {
        if (isAdmin) {
            plugin.getHomelandManager().resetOverworldAdmin(p, targetUuid, homelandName, worldType,
                    () -> {}, () -> {});
        } else {
            plugin.getHomelandManager().resetOverworld(p, homelandName, worldType,
                    () -> {}, () -> {});
        }
    }

    private ItemStack createConfirmItem(String worldTypeName) {
        ItemStack item = new ItemStack(plugin.getConfigManager().getGUIConfig().getReset().getConfirmMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        ConfigManager config = plugin.getConfigManager();
        meta.displayName(MessageUtil.guiName(config.getMessage(
                "gui.reset-overworld-confirm-name", "type", worldTypeName)));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        addCostLore(lore, config);
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.reset-overworld-confirm-warning")));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.reset-confirm-click")));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.reset-confirm-cancel")));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void addCostLore(List<Component> lore, ConfigManager config) {
        if (isAdmin) return;
        double money = config.getResetOverworldMoney();
        int points = config.getResetOverworldPoints();
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

    private ItemStack createDefaultItem(ConfigManager config) {
        ItemStack item = new ItemStack(cfg.getDefaultMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(config.getMessage("gui.create-type-default-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.create-type-default-lore")));
        addCostLore(lore, config);
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.reset-type-click")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createVoidItem(ConfigManager config) {
        ItemStack item = new ItemStack(cfg.getVoidMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(config.getMessage("gui.create-type-void-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.create-type-void-lore")));
        addCostLore(lore, config);
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.reset-type-click")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createFlatItem(ConfigManager config) {
        ItemStack item = new ItemStack(cfg.getFlatMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(config.getMessage("gui.create-type-flat-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.create-type-flat-lore")));
        addCostLore(lore, config);
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.reset-type-click")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
