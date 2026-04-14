package dev.user.homeland.gui;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;

public abstract class AbstractGUI implements InventoryHolder {

    protected final SimpleHomelandPlugin plugin;
    protected final Player player;
    protected final Component title;
    protected final int size;
    protected Inventory inventory;
    protected final Map<Integer, GUIAction> actions;
    protected int currentPage = 0;
    private boolean aborted = false;

    @FunctionalInterface
    public interface GUIAction {
        void execute(Player player, InventoryClickEvent event);
    }

    public AbstractGUI(SimpleHomelandPlugin plugin, Player player, String title, int size) {
        this.plugin = plugin;
        this.player = player;
        this.title = MessageUtil.toComponent(title).decoration(TextDecoration.ITALIC, false);
        if (size % 9 != 0 || size < 9 || size > 54) {
            plugin.getLogger().warning("GUI配置错误: size " + size + " 无效（应为9/18/27/36/45/54），使用默认值54");
            size = 54;
        }
        this.size = size;
        this.actions = new HashMap<>();
    }

    public abstract void initialize();

    public void open() {
        inventory = plugin.getServer().createInventory(this, size, title);
        aborted = false;
        initialize();
        if (aborted) return;
        GUIManager.registerGUI(player.getUniqueId(), this);
        player.openInventory(inventory);
    }

    protected void setItem(int slot, ItemStack item) {
        if (slot < 0 || slot >= size) {
            plugin.getLogger().warning("GUI配置错误: slot " + slot + " 超出范围 [0, " + (size - 1) + "]");
            return;
        }
        inventory.setItem(slot, item);
    }

    protected void setItemIfPermitted(int slot, ItemStack item, String permission, GUIAction action) {
        if (player.hasPermission(permission)) {
            setItem(slot, item, action);
        }
    }

    protected void setItem(int slot, ItemStack item, GUIAction action) {
        if (slot < 0 || slot >= size) {
            plugin.getLogger().warning("GUI配置错误: slot " + slot + " 超出范围 [0, " + (size - 1) + "]");
            return;
        }
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        }
    }

    protected void fillBorder(ItemStack item) {
        int rows = size / 9;
        for (int i = 0; i < size; i++) {
            int row = i / 9;
            int col = i % 9;
            if (row == 0 || row == rows - 1 || col == 0 || col == 8) {
                if (inventory.getItem(i) == null) {
                    inventory.setItem(i, item);
                }
            }
        }
    }

    protected void fillEmpty(ItemStack item) {
        for (int i = 0; i < size; i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, item);
            }
        }
    }

    public GUIAction getAction(int slot) {
        return actions.get(slot);
    }

    public void close() {
        GUIManager.unregisterGUI(player.getUniqueId());
        player.closeInventory();
    }

    /**
     * 在 initialize() 中调用，表示 GUI 不应继续打开。
     * 用于 homeland 不存在等需要终止打开流程的场景。
     */
    protected void abort() {
        this.aborted = true;
    }

    public void refresh() {
        actions.clear();
        inventory.clear();
        initialize();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Player getPlayer() {
        return player;
    }

    public SimpleHomelandPlugin getPlugin() {
        return plugin;
    }

    protected void setPageAndRefresh(int page) {
        this.currentPage = page;
        refresh();
    }

    protected void addPageButtons(int totalItems, int itemsPerPage, int prevSlot, int nextSlot) {
        Material pageMaterial = plugin.getConfigManager().getGUIConfig().getGlobal().getPageButtonMaterial();
        int totalPages = (int) Math.ceil((double) totalItems / itemsPerPage);
        if (totalPages <= 1) return;

        if (currentPage > 0) {
            setItem(prevSlot, createPageItem(pageMaterial, "&a上一页 (" + currentPage + "/" + totalPages + ")"),
                    (p, e) -> setPageAndRefresh(currentPage - 1));
        }
        if (currentPage < totalPages - 1) {
            setItem(nextSlot, createPageItem(pageMaterial, "&a下一页 (" + (currentPage + 2) + "/" + totalPages + ")"),
                    (p, e) -> setPageAndRefresh(currentPage + 1));
        }
    }

    protected ItemStack createPageItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(MessageUtil.guiName(name));
        item.setItemMeta(meta);
        return item;
    }

    protected ItemStack createBorderItem() {
        Material borderMaterial = plugin.getConfigManager().getGUIConfig().getGlobal().getBorderMaterial();
        ItemStack item = new ItemStack(borderMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.space().decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return item;
    }

    protected ItemStack createCloseItem() {
        Material closeMaterial = plugin.getConfigManager().getGUIConfig().getGlobal().getCloseMaterial();
        ItemStack item = new ItemStack(closeMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(MessageUtil.guiName(plugin.getConfigManager().getMessage("gui.close-item-name")));
        item.setItemMeta(meta);
        return item;
    }

    protected ItemStack createBackItem(Material material, String messageKey) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(MessageUtil.guiName(plugin.getConfigManager().getMessage(messageKey)));
        item.setItemMeta(meta);
        return item;
    }
}