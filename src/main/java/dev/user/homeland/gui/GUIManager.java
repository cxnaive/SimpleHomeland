package dev.user.homeland.gui;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GUIManager {

    private static final Map<UUID, AbstractGUI> openGUIs = new ConcurrentHashMap<>();

    public static void registerGUI(UUID uuid, AbstractGUI gui) {
        openGUIs.put(uuid, gui);
    }

    public static void unregisterGUI(UUID uuid) {
        openGUIs.remove(uuid);
    }

    public static AbstractGUI getOpenGUI(UUID uuid) {
        return openGUIs.get(uuid);
    }

    public static boolean hasOpenGUI(UUID uuid) {
        return openGUIs.containsKey(uuid);
    }

    public static void closeAll() {
        for (AbstractGUI gui : openGUIs.values()) {
            Player player = gui.getPlayer();
            if (player.isOnline()) {
                player.getScheduler().execute(gui.getPlugin(), player::closeInventory, () -> {}, 0L);
            }
        }
        openGUIs.clear();
    }
}