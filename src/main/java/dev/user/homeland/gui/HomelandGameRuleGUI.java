package dev.user.homeland.gui;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.config.ConfigManager;
import dev.user.homeland.config.ConfigManager.GameRuleConfig;
import dev.user.homeland.config.GUIConfig;
import dev.user.homeland.model.Homeland;
import dev.user.homeland.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.ChatColor;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 家园游戏规则设置 GUI（分页）
 */
public class HomelandGameRuleGUI extends AbstractGUI {

    private final String homelandName;
    private final UUID ownerUuid;
    private final String ownerName;
    private final boolean isAdmin;
    private final ConfigManager config;
    private final GUIConfig.GameRule cfg;

    public HomelandGameRuleGUI(SimpleHomelandPlugin plugin, Player player, String homelandName) {
        super(plugin, player, plugin.getConfigManager().getMessage("gui.gamerule-title", "name", homelandName),
                plugin.getConfigManager().getGUIConfig().getGamerule().getSize());
        this.homelandName = homelandName;
        this.ownerUuid = player.getUniqueId();
        this.ownerName = null;
        this.isAdmin = false;
        this.config = plugin.getConfigManager();
        this.cfg = plugin.getConfigManager().getGUIConfig().getGamerule();
    }

    private HomelandGameRuleGUI(SimpleHomelandPlugin plugin, Player player, UUID ownerUuid, String ownerName, String homelandName) {
        super(plugin, player, plugin.getConfigManager().getMessage("gui.gamerule-title", "name", homelandName),
                plugin.getConfigManager().getGUIConfig().getGamerule().getSize());
        this.homelandName = homelandName;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.isAdmin = true;
        this.config = plugin.getConfigManager();
        this.cfg = plugin.getConfigManager().getGUIConfig().getGamerule();
    }

    public static void open(SimpleHomelandPlugin plugin, Player player, String homelandName) {
        new HomelandGameRuleGUI(plugin, player, homelandName).open();
    }

    public static void openAdmin(SimpleHomelandPlugin plugin, Player admin, UUID ownerUuid, String ownerName, String homelandName) {
        new HomelandGameRuleGUI(plugin, admin, ownerUuid, ownerName, homelandName).open();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void initialize() {
        actions.clear();

        Optional<Homeland> optHomeland = plugin.getHomelandManager().getHomeland(ownerUuid, homelandName);
        if (optHomeland.isEmpty()) {
            player.sendMessage(MessageUtil.toComponent(config.getMessage("homeland-not-found", "name", homelandName)));
            abort();
            return;
        }

        Homeland homeland = optHomeland.get();

        ItemStack border = createBorderItem();
        fillBorder(border);

        // 获取主世界以读取当前 gamerule 值
        World overworld = plugin.getHomelandManager().getHomelandWorld(homeland.getWorldKey());

        List<GameRuleConfig> gamerules = config.getEnabledGameruleConfigs();

        if (overworld == null || gamerules.isEmpty()) {
            setItem(cfg.getEmptySlot(), createEmptyItem(gamerules.isEmpty()), null);
        } else {
            int[] pageSlots = cfg.getPageSlots();
            int start = currentPage * cfg.getItemsPerPage();
            for (int i = 0; i < cfg.getItemsPerPage() && i < pageSlots.length; i++) {
                int idx = start + i;
                if (idx >= gamerules.size()) break;

                GameRuleConfig grc = gamerules.get(idx);
                String currentValue = getGameruleValue(overworld, grc);
                setItem(pageSlots[i], createGameruleItem(grc, currentValue), (p, e) -> {
                    handleClick(grc, currentValue, p, e);
                });
            }

            addGamerulePageButtons(gamerules.size());
        }

        // 难度设置按钮
        setItem(cfg.getDifficultySlot(), createDifficultyItem(overworld), (p, e) -> {
            if (overworld == null) return;
            Difficulty current = overworld.getDifficulty();
            Difficulty next;
            if (e.getClick() == ClickType.RIGHT) {
                next = cycleDifficulty(current, true);
            } else {
                next = cycleDifficulty(current, false);
            }
            handleDifficultyClick(next, p);
        });

        // 底部导航
        if (isAdmin) {
            setItem(cfg.getBackSlot(), createBackItem(
                    plugin.getConfigManager().getGUIConfig().getGlobal().getBackMaterial(),
                    "gui.admin-back-player-homelands"), (p, e) -> {
                close();
                HomelandManageGUI.openAdmin(plugin, p, ownerUuid, ownerName, homelandName);
            });
        } else {
            setItem(cfg.getBackSlot(), createBackItem(
                    plugin.getConfigManager().getGUIConfig().getGlobal().getBackMaterial(), "gui.back-item-name"), (p, e) -> {
                close();
                HomelandManageGUI.open(plugin, p, homelandName);
            });
        }

        setItem(cfg.getCloseSlot(), createCloseItem(), (p, e) -> close());
    }

    private void handleClick(GameRuleConfig grc, String currentValue, Player p, InventoryClickEvent e) {
        String newValue;
        if (grc.isBooleanType()) {
            newValue = String.valueOf(!Boolean.parseBoolean(currentValue));
        } else {
            int current = Integer.parseInt(currentValue);
            if (e.getClick() == ClickType.RIGHT) {
                newValue = String.valueOf(Math.max(0, current - 1));
            } else {
                newValue = String.valueOf(current + 1);
            }
        }

        if (isAdmin) {
            plugin.getHomelandManager().setGameruleAdmin(p, ownerUuid, homelandName, grc, newValue,
                    () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L),
                    () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L));
        } else {
            plugin.getHomelandManager().setGamerule(p, homelandName, grc, newValue,
                    () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L),
                    () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L));
        }
    }

    private void addGamerulePageButtons(int totalItems) {
        int totalPages = (int) Math.ceil((double) totalItems / cfg.getItemsPerPage());
        if (totalPages <= 1) return;

        GUIConfig.Global global = plugin.getConfigManager().getGUIConfig().getGlobal();

        if (currentPage > 0) {
            setItem(cfg.getPrevSlot(), createPageItem(
                    global.getPageButtonMaterial(), config.getMessage("gui.gamerule-page-prev")), (p, e) -> {
                currentPage--;
                refresh();
            });
        }

        // 页码显示
        setItem(cfg.getPageInfoSlot(), createPageInfoItem(currentPage + 1, totalPages), null);

        if (currentPage < totalPages - 1) {
            setItem(cfg.getNextSlot(), createPageItem(
                    global.getPageButtonMaterial(), config.getMessage("gui.gamerule-page-next")), (p, e) -> {
                currentPage++;
                refresh();
            });
        }
    }

    private ItemStack createPageInfoItem(int page, int total) {
        Material pageInfoMaterial = plugin.getConfigManager().getGUIConfig().getGlobal().getPageInfoMaterial();
        ItemStack item = new ItemStack(pageInfoMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(MessageUtil.guiName(
                config.getMessage("gui.gamerule-page-info",
                        "page", String.valueOf(page),
                        "total", String.valueOf(total))));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createGameruleItem(GameRuleConfig grc, String currentValue) {
        ItemStack item = new ItemStack(grc.getMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(grc.getName()));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));

        // 从配置添加描述行
        for (String line : grc.getLore()) {
            lore.add(MessageUtil.guiLore(ChatColor.translateAlternateColorCodes('&', line)));
        }

        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));

        // 当前值和操作提示
        if (grc.isBooleanType()) {
            boolean val = Boolean.parseBoolean(currentValue);
            lore.add(MessageUtil.guiLore(config.getMessage(val ? "gui.gamerule-value-on" : "gui.gamerule-value-off")));
            lore.add(MessageUtil.guiLore(config.getMessage("gui.gamerule-click-toggle")));
        } else {
            lore.add(MessageUtil.guiLore(config.getMessage("gui.gamerule-value", "value", currentValue)));
            lore.add(MessageUtil.guiLore(config.getMessage("gui.gamerule-click-adjust")));
        }

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createEmptyItem(boolean noGamerules) {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (noGamerules) {
            meta.displayName(MessageUtil.guiName("&7没有可配置的游戏规则"));
        } else {
            meta.displayName(MessageUtil.guiName("&c家园世界未加载"));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
            lore.add(MessageUtil.guiLore(config.getMessage("gui.gamerule-world-not-loaded")));
            meta.lore(lore);
        }

        item.setItemMeta(meta);
        return item;
    }

    @SuppressWarnings("unchecked")
    private String getGameruleValue(World world, GameRuleConfig grc) {
        if (grc.isBooleanType()) {
            return String.valueOf(world.getGameRuleValue((GameRule<Boolean>) grc.getGameRule()));
        } else {
            return String.valueOf(world.getGameRuleValue((GameRule<Integer>) grc.getGameRule()));
        }
    }

    private Difficulty cycleDifficulty(Difficulty current, boolean reverse) {
        Difficulty[] order = Difficulty.values(); // PEACEFUL, EASY, NORMAL, HARD
        int idx = current.ordinal();
        if (reverse) {
            idx = (idx - 1 + order.length) % order.length;
        } else {
            idx = (idx + 1) % order.length;
        }
        return order[idx];
    }

    private ItemStack createDifficultyItem(World overworld) {
        ItemStack item = new ItemStack(cfg.getDifficultyMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (overworld == null) {
            meta.displayName(MessageUtil.guiName(config.getMessage("gui.difficulty-item-name")));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
            lore.add(MessageUtil.guiLore(config.getMessage("gui.difficulty-world-not-loaded")));
            meta.lore(lore);
            item.setItemMeta(meta);
            return item;
        }

        Difficulty diff = overworld.getDifficulty();
        String diffDisplay = getDifficultyDisplayName(diff);

        meta.displayName(MessageUtil.guiName(config.getMessage("gui.difficulty-item-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.difficulty-item-lore", "difficulty", diffDisplay)));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.difficulty-item-click")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String getDifficultyDisplayName(Difficulty diff) {
        switch (diff) {
            case PEACEFUL: return config.getMessage("gui.difficulty-peaceful");
            case EASY: return config.getMessage("gui.difficulty-easy");
            case NORMAL: return config.getMessage("gui.difficulty-normal");
            case HARD: return config.getMessage("gui.difficulty-hard");
            default: return diff.name();
        }
    }

    private void handleDifficultyClick(Difficulty newDifficulty, Player p) {
        String diffDisplay = getDifficultyDisplayName(newDifficulty);

        Runnable onSuccess = () -> p.getScheduler().execute(plugin, () -> {
            MessageUtil.send(p, config.getMessage("gui.difficulty-set-success",
                    "name", homelandName, "difficulty", diffDisplay));
            refresh();
        }, () -> {}, 0L);

        Runnable onFailure = () -> p.getScheduler().execute(plugin, () -> {
            MessageUtil.send(p, config.getMessage("gui.difficulty-set-failed"));
            refresh();
        }, () -> {}, 0L);

        if (isAdmin) {
            plugin.getHomelandManager().setDifficultyAdmin(p, ownerUuid, homelandName, newDifficulty, onSuccess, onFailure);
        } else {
            plugin.getHomelandManager().setDifficulty(p, homelandName, newDifficulty, onSuccess, onFailure);
        }
    }
}
