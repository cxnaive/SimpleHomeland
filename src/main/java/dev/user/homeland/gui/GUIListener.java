package dev.user.homeland.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;

public class GUIListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        AbstractGUI gui = GUIManager.getOpenGUI(player.getUniqueId());
        if (gui == null) return;
        if (event.getInventory() != gui.getInventory()) return;

        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= gui.getInventory().getSize()) return;

        AbstractGUI.GUIAction action = gui.getAction(slot);
        if (action != null) {
            action.execute(player, event);
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        AbstractGUI gui = GUIManager.getOpenGUI(player.getUniqueId());
        if (gui == null) return;
        if (event.getInventory() != gui.getInventory()) return;
        if (event.isCancelled()) {
            GUIManager.unregisterGUI(player.getUniqueId());
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        AbstractGUI gui = GUIManager.getOpenGUI(player.getUniqueId());
        if (gui == null) return;
        if (event.getInventory() != gui.getInventory()) return;
        GUIManager.unregisterGUI(player.getUniqueId());
    }
}