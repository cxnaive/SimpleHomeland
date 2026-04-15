package dev.user.homeland.gui;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.config.GUIConfig;
import dev.user.homeland.model.Homeland;
import dev.user.homeland.model.VisitorFlag;
import dev.user.homeland.model.VisitorFlags;
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
import java.util.function.BiConsumer;

/**
 * 访客权限设置 GUI（分页）
 * 支持两种模式：家园访客权限、大厅访客权限
 */
public class HomelandVisitorFlagGUI extends AbstractGUI {

    private final GUIConfig.VisitorFlag cfg;
    private final VisitorFlags flags;
    private final BiConsumer<VisitorFlag, Boolean> onSave;
    private final Runnable onBack;

    private HomelandVisitorFlagGUI(SimpleHomelandPlugin plugin, Player player, String title,
                                   VisitorFlags flags, BiConsumer<VisitorFlag, Boolean> onSave, Runnable onBack) {
        super(plugin, player, title,
                plugin.getConfigManager().getGUIConfig().getVisitorFlag().getSize());
        this.cfg = plugin.getConfigManager().getGUIConfig().getVisitorFlag();
        this.flags = flags;
        this.onSave = onSave;
        this.onBack = onBack;
    }

    /**
     * 家园访客权限入口
     */
    public static void open(SimpleHomelandPlugin plugin, Player player, String homelandName) {
        Optional<Homeland> optHomeland = plugin.getHomelandManager().getHomeland(player.getUniqueId(), homelandName);
        if (optHomeland.isEmpty()) return;

        Homeland homeland = optHomeland.get();
        String title = plugin.getConfigManager().getMessage("gui.visitor-flag-title", "name", homelandName);

        final HomelandVisitorFlagGUI[] holder = new HomelandVisitorFlagGUI[1];
        HomelandVisitorFlagGUI gui = new HomelandVisitorFlagGUI(plugin, player, title,
                homeland.getVisitorFlags(),
                (flag, value) -> plugin.getHomelandManager().updateVisitorFlag(player, homelandName, flag, value,
                        () -> player.getScheduler().execute(plugin, () -> holder[0].refresh(), () -> {}, 0L),
                        () -> player.getScheduler().execute(plugin, () -> holder[0].refresh(), () -> {}, 0L)),
                () -> HomelandManageGUI.open(plugin, player, homelandName));
        holder[0] = gui;
        gui.open();
    }

    /**
     * 大厅访客权限入口
     */
    public static void openForLobby(SimpleHomelandPlugin plugin, Player player) {
        if (!player.hasPermission("simplehomeland.admin")) return;

        VisitorFlags lobbyFlags = plugin.getConfigManager().getLobbyVisitorFlags();
        String title = plugin.getConfigManager().getMessage("gui.lobby-visitor-flag-title");

        final HomelandVisitorFlagGUI[] holder = new HomelandVisitorFlagGUI[1];
        HomelandVisitorFlagGUI gui = new HomelandVisitorFlagGUI(plugin, player, title,
                lobbyFlags,
                (flag, value) -> {
                    lobbyFlags.set(flag, value);
                    plugin.getConfigManager().saveLobbyVisitorFlags(lobbyFlags);
                    String valueStr = value ? "允许" : "禁止";
                    MessageUtil.send(player, plugin.getConfigManager().getMessage(
                            "visitor-flag-updated", "flag", flag.getDisplayName(), "value", valueStr));
                    player.getScheduler().execute(plugin, () -> holder[0].refresh(), () -> {}, 0L);
                },
                () -> HomelandAdminGUI.open(plugin, player));
        holder[0] = gui;
        gui.open();
    }

    /**
     * admin 管理指定家园的访客权限入口
     */
    public static void openAdmin(SimpleHomelandPlugin plugin, Player admin, UUID ownerUuid, String ownerName, String homelandName) {
        Optional<Homeland> optHomeland = plugin.getHomelandManager().getHomeland(ownerUuid, homelandName);
        if (optHomeland.isEmpty()) return;

        Homeland homeland = optHomeland.get();
        String title = plugin.getConfigManager().getMessage("gui.visitor-flag-title", "name", homelandName);

        final HomelandVisitorFlagGUI[] holder = new HomelandVisitorFlagGUI[1];
        HomelandVisitorFlagGUI gui = new HomelandVisitorFlagGUI(plugin, admin, title,
                homeland.getVisitorFlags(),
                (flag, value) -> plugin.getHomelandManager().updateVisitorFlagAdmin(admin, ownerUuid, homelandName, flag, value,
                        () -> admin.getScheduler().execute(plugin, () -> holder[0].refresh(), () -> {}, 0L),
                        () -> admin.getScheduler().execute(plugin, () -> holder[0].refresh(), () -> {}, 0L)),
                () -> HomelandManageGUI.openAdmin(plugin, admin, ownerUuid, ownerName, homelandName));
        holder[0] = gui;
        gui.open();
    }

    @Override
    public void initialize() {
        actions.clear();

        ItemStack border = createBorderItem();
        fillBorder(border);

        VisitorFlag[] flagValues = VisitorFlag.values();
        int[] contentSlots = cfg.getContentSlots();
        int start = currentPage * cfg.getItemsPerPage();

        for (int i = 0; i < cfg.getItemsPerPage() && i < contentSlots.length; i++) {
            int idx = start + i;
            if (idx >= flagValues.length) break;

            VisitorFlag flag = flagValues[idx];
            boolean value = flags.get(flag);
            setItem(contentSlots[i], createFlagItem(flag, value), (p, e) -> {
                onSave.accept(flag, !value);
            });
        }

        // 翻页按钮
        addPageButtons(flagValues.length, cfg.getItemsPerPage(), cfg.getPrevSlot(), cfg.getNextSlot());

        // 页码信息
        int totalPages = Math.max(1, (int) Math.ceil((double) flagValues.length / cfg.getItemsPerPage()));
        ItemStack pageInfo = createPageInfoItem(currentPage + 1, totalPages);
        setItem(cfg.getPageInfoSlot(), pageInfo, null);

        // 导航
        setItem(cfg.getBackSlot(), createBackItem(
                plugin.getConfigManager().getGUIConfig().getGlobal().getBackMaterial(), "gui.back-item-name"), (p, e) -> {
            close();
            onBack.run();
        });

        setItem(cfg.getCloseSlot(), createCloseItem(), (p, e) -> close());
    }

    private ItemStack createFlagItem(VisitorFlag flag, boolean value) {
        ItemStack item = new ItemStack(flag.getMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String statusColor = value ? "&a" : "&c";
        String statusText = value ? "✔ " : "✘ ";
        meta.displayName(MessageUtil.guiName(statusColor + statusText + flag.getDisplayName()));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(plugin.getConfigManager().getMessage(
                value ? "gui.visitor-flag-enabled" : "gui.visitor-flag-disabled")));
        lore.add(MessageUtil.guiLore(plugin.getConfigManager().getMessage("gui.visitor-flag-click-toggle")));

        meta.lore(lore);
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
