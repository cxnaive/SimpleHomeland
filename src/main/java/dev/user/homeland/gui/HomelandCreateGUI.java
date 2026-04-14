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
 * 地形类型选择 GUI
 */
public class HomelandCreateGUI extends AbstractGUI {

    private final String homelandName;
    private final GUIConfig.Create cfg;
    private final boolean adminMode;
    private final UUID adminTargetUuid;
    private final String adminTargetName;

    public HomelandCreateGUI(SimpleHomelandPlugin plugin, Player player, String homelandName) {
        super(plugin, player, plugin.getConfigManager().getMessage("gui.create-type-title", "name", homelandName),
                plugin.getConfigManager().getGUIConfig().getCreate().getSize());
        this.homelandName = homelandName;
        this.cfg = plugin.getConfigManager().getGUIConfig().getCreate();
        this.adminMode = false;
        this.adminTargetUuid = null;
        this.adminTargetName = null;
    }

    public HomelandCreateGUI(SimpleHomelandPlugin plugin, Player player, String homelandName,
                              UUID targetUuid, String targetName) {
        super(plugin, player, plugin.getConfigManager().getMessage("gui.create-type-title", "name", homelandName),
                plugin.getConfigManager().getGUIConfig().getCreate().getSize());
        this.homelandName = homelandName;
        this.cfg = plugin.getConfigManager().getGUIConfig().getCreate();
        this.adminMode = true;
        this.adminTargetUuid = targetUuid;
        this.adminTargetName = targetName;
    }

    public static void open(SimpleHomelandPlugin plugin, Player player, String homelandName) {
        new HomelandCreateGUI(plugin, player, homelandName).open();
    }

    public static void openAdmin(SimpleHomelandPlugin plugin, Player admin, String homelandName,
                                  UUID targetUuid, String targetName) {
        new HomelandCreateGUI(plugin, admin, homelandName, targetUuid, targetName).open();
    }

    @Override
    public void initialize() {
        actions.clear();

        ItemStack border = createBorderItem();
        fillBorder(border);

        // 地形选择（需要 create 权限）
        if (player.hasPermission("simplehomeland.homeland.create")) {
            // 默认地形
            setItem(cfg.getDefaultSlot(), createDefaultItem(), (p, e) -> {
                close();
                createHomeland(p, "default");
            });

            // 空岛
            setItem(cfg.getVoidSlot(), createVoidItem(), (p, e) -> {
                close();
                createHomeland(p, "void");
            });

            // 超平坦
            setItem(cfg.getFlatSlot(), createFlatItem(), (p, e) -> {
                close();
                createHomeland(p, "flat");
            });
        }

        // 关闭按钮
        setItem(cfg.getCloseSlot(), createCloseItem(), (p, e) -> close());
    }

    private void createHomeland(Player p, String worldType) {
        if (adminMode) {
            plugin.getHomelandManager().createHomelandAdmin(p, adminTargetUuid, homelandName, worldType,
                    () -> {}, () -> {});
        } else {
            plugin.getHomelandManager().createHomeland(p, homelandName, worldType,
                    () -> {}, () -> {});
        }
    }

    private ItemStack createDefaultItem() {
        ItemStack item = new ItemStack(cfg.getDefaultMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        ConfigManager config = plugin.getConfigManager();
        meta.displayName(MessageUtil.guiName(config.getMessage("gui.create-type-default-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.create-type-default-lore")));
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.create-type-click")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createVoidItem() {
        ItemStack item = new ItemStack(cfg.getVoidMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        ConfigManager config = plugin.getConfigManager();
        meta.displayName(MessageUtil.guiName(config.getMessage("gui.create-type-void-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.create-type-void-lore")));
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.create-type-click")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createFlatItem() {
        ItemStack item = new ItemStack(cfg.getFlatMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        ConfigManager config = plugin.getConfigManager();
        meta.displayName(MessageUtil.guiName(config.getMessage("gui.create-type-flat-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.create-type-flat-lore")));
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.create-type-click")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
