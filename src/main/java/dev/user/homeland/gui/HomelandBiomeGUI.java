package dev.user.homeland.gui;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.config.ConfigManager;
import dev.user.homeland.config.GUIConfig;
import dev.user.homeland.model.Homeland;
import dev.user.homeland.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class HomelandBiomeGUI extends AbstractGUI {

    private final GUIConfig.Biome cfg;
    private final String homelandName;
    private ConfigManager.BiomeEntry pendingBiome = null;

    private HomelandBiomeGUI(SimpleHomelandPlugin plugin, Player player, String title, String homelandName) {
        super(plugin, player, title,
                plugin.getConfigManager().getGUIConfig().getBiome().getSize());
        this.cfg = plugin.getConfigManager().getGUIConfig().getBiome();
        this.homelandName = homelandName;
    }

    public static void open(SimpleHomelandPlugin plugin, Player player, String homelandName) {
        String title = plugin.getConfigManager().getMessage("gui.biome-title", "name", homelandName);
        new HomelandBiomeGUI(plugin, player, title, homelandName).open();
    }

    @Override
    public void initialize() {
        actions.clear();

        ItemStack border = createBorderItem();
        fillBorder(border);

        List<ConfigManager.BiomeEntry> entries = plugin.getConfigManager().getBiomeEntries();

        // 当前群系信息
        Biome currentBiome = null;
        World world = player.getWorld();
        Homeland homeland = plugin.getHomelandManager().getHomelandByWorldKey(
                world.getKey().getKey());
        if (homeland != null) {
            currentBiome = world.getBiome(player.getLocation());
        }
        setItem(cfg.getCurrentBiomeSlot(), createCurrentBiomeItem(currentBiome), null);

        // 群系列表
        int[] pageSlots = cfg.getPageSlots();
        int start = currentPage * cfg.getItemsPerPage();

        for (int i = 0; i < cfg.getItemsPerPage() && i < pageSlots.length; i++) {
            int idx = start + i;
            if (idx >= entries.size()) break;

            ConfigManager.BiomeEntry entry = entries.get(idx);
            boolean isCurrent = currentBiome == entry.getBiome();
            boolean isPending = pendingBiome != null && pendingBiome.getBiome() == entry.getBiome();

            if (isPending) {
                setItem(pageSlots[i], createConfirmItem(entry), (p, e) -> {
                    handleConfirm(p, entry);
                });
            } else {
                setItem(pageSlots[i], createEntryItem(entry, isCurrent), (p, e) -> {
                    if (isCurrent) return;
                    pendingBiome = entry;
                    refresh();
                });
            }
        }

        // 空列表提示
        if (entries.isEmpty()) {
            ItemStack emptyItem = new ItemStack(Material.BARRIER);
            ItemMeta meta = emptyItem.getItemMeta();
            if (meta != null) {
                meta.displayName(MessageUtil.guiName(
                        plugin.getConfigManager().getMessage("gui.biome-empty")));
                emptyItem.setItemMeta(meta);
            }
            setItem(cfg.getEmptySlot(), emptyItem, null);
        }

        // 翻页
        addPageButtons(entries.size(), cfg.getItemsPerPage(), cfg.getPrevSlot(), cfg.getNextSlot());

        // 页码
        int totalPages = Math.max(1, (int) Math.ceil((double) entries.size() / cfg.getItemsPerPage()));
        setItem(cfg.getPageInfoSlot(), createPageInfoItem(currentPage + 1, totalPages), null);

        // 返回
        setItem(cfg.getBackSlot(), createBackItem(
                plugin.getConfigManager().getGUIConfig().getGlobal().getBackMaterial(), "gui.back-item-name"), (p, e) -> {
            close();
            HomelandManageGUI.open(plugin, p, homelandName);
        });

        // 关闭
        setItem(cfg.getCloseSlot(), createCloseItem(), (p, e) -> close());
    }

    private void handleConfirm(Player player, ConfigManager.BiomeEntry entry) {
        ConfigManager config = plugin.getConfigManager();

        // 再次验证：玩家必须在自己的家园主世界中
        World world = player.getWorld();
        String worldKey = world.getKey().getKey();

        // 检查是否在 nether/end 维度
        if (worldKey.endsWith("_nether") || worldKey.endsWith("_the_end")) {
            MessageUtil.send(player, config.getMessage("gui.biome-not-in-overworld"));
            pendingBiome = null;
            refresh();
            return;
        }

        Homeland homeland = plugin.getHomelandManager().getHomelandByWorldKey(worldKey);
        if (homeland == null) {
            MessageUtil.send(player, config.getMessage("gui.biome-not-in-homeland"));
            pendingBiome = null;
            refresh();
            return;
        }

        if (!homeland.getOwnerUuid().equals(player.getUniqueId())) {
            MessageUtil.send(player, config.getMessage("gui.biome-not-owner"));
            pendingBiome = null;
            refresh();
            return;
        }

        // 检查是否已经是该群系
        Biome currentBiome = world.getBiome(player.getLocation());
        if (currentBiome == entry.getBiome()) {
            MessageUtil.send(player, config.getMessage("gui.biome-already-same", "biome", entry.getName()));
            pendingBiome = null;
            refresh();
            return;
        }

        // 计算费用
        double money = entry.hasCostOverride() && entry.getCostOverrideMoney() >= 0
                ? entry.getCostOverrideMoney() : config.getBiomeChangeMoney();
        int points = entry.hasCostOverride() && entry.getCostOverridePoints() >= 0
                ? entry.getCostOverridePoints() : config.getBiomeChangePoints();

        // 扣费
        if (!plugin.getHomelandManager().checkAndWithdrawEconomy(player, money, points)) {
            pendingBiome = null;
            refresh();
            return;
        }

        // 设置群系
        try {
            org.bukkit.Chunk chunk = player.getLocation().getChunk();
            int chunkX = chunk.getX();
            int chunkZ = chunk.getZ();
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight();
            Biome targetBiome = entry.getBiome();

            for (int x = chunkX << 4; x < (chunkX << 4) + 16; x += 4) {
                for (int z = chunkZ << 4; z < (chunkZ << 4) + 16; z += 4) {
                    for (int y = minY; y < maxY; y += 4) {
                        world.setBiome(x, y, z, targetBiome);
                    }
                }
            }

            world.refreshChunk(chunkX, chunkZ);

            MessageUtil.send(player, config.getMessage("gui.biome-success", "biome", entry.getName()));
            close();
        } catch (Exception e) {
            plugin.getLogger().warning("更改群系失败: " + e.getMessage());
            // 退还费用
            plugin.getHomelandManager().refundEconomy(player, money, points);
            MessageUtil.send(player, config.getMessage("gui.biome-failed"));
            pendingBiome = null;
            refresh();
        }
    }

    private ItemStack createCurrentBiomeItem(Biome currentBiome) {
        ConfigManager config = plugin.getConfigManager();
        ItemStack item = new ItemStack(cfg.getCurrentBiomeMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String biomeName = "未知";
        if (currentBiome != null) {
            for (ConfigManager.BiomeEntry entry : config.getBiomeEntries()) {
                if (entry.getBiome() == currentBiome) {
                    biomeName = entry.getName();
                    break;
                }
            }
            if (biomeName.equals("未知")) {
                @SuppressWarnings("deprecation")
                String biomeKey = currentBiome.name();
                biomeName = biomeKey;
            }
        }

        meta.displayName(MessageUtil.guiName(config.getMessage("gui.biome-current", "biome", biomeName)));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        org.bukkit.Location loc = player.getLocation();
        lore.add(MessageUtil.guiLore(config.getMessage("gui.biome-current-chunk",
                "x", String.valueOf(loc.getChunk().getX()),
                "z", String.valueOf(loc.getChunk().getZ()))));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createEntryItem(ConfigManager.BiomeEntry entry, boolean isCurrent) {
        ConfigManager config = plugin.getConfigManager();
        ItemStack item = new ItemStack(entry.getMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(config.getMessage("gui.biome-entry-name", "name", entry.getName())));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));

        // 自定义 lore
        if (entry.getLore() != null) {
            for (String line : entry.getLore()) {
                lore.add(MessageUtil.guiLore(org.bukkit.ChatColor.translateAlternateColorCodes('&', line)));
            }
        }

        // 费用
        double money = entry.hasCostOverride() && entry.getCostOverrideMoney() >= 0
                ? entry.getCostOverrideMoney() : config.getBiomeChangeMoney();
        int points = entry.hasCostOverride() && entry.getCostOverridePoints() >= 0
                ? entry.getCostOverridePoints() : config.getBiomeChangePoints();

        if (money > 0 && points > 0) {
            lore.add(MessageUtil.guiLore(config.getMessage("gui.biome-entry-cost",
                    "money", String.format("%.2f", money),
                    "points", String.valueOf(points))));
        } else if (money > 0) {
            lore.add(MessageUtil.guiLore(config.getMessage("gui.biome-entry-cost-money",
                    "money", String.format("%.2f", money))));
        } else if (points > 0) {
            lore.add(MessageUtil.guiLore(config.getMessage("gui.biome-entry-cost-points",
                    "points", String.valueOf(points))));
        } else {
            lore.add(MessageUtil.guiLore(config.getMessage("gui.biome-entry-free")));
        }

        if (isCurrent) {
            lore.add(MessageUtil.guiLore(config.getMessage("gui.biome-entry-current")));
        } else {
            lore.add(MessageUtil.guiLore(config.getMessage("gui.biome-entry-click")));
        }

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createConfirmItem(ConfigManager.BiomeEntry entry) {
        ConfigManager config = plugin.getConfigManager();
        ItemStack item = new ItemStack(Material.YELLOW_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(config.getMessage("gui.biome-confirm-name", "name", entry.getName())));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.biome-confirm-click")));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.biome-confirm-cancel")));

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
                plugin.getConfigManager().getMessage("gui.biome-page-info",
                        "page", String.valueOf(page),
                        "total", String.valueOf(total))));
        item.setItemMeta(meta);
        return item;
    }
}
