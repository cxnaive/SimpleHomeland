package dev.user.homeland.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class MessageUtil {

    private static final LegacyComponentSerializer SECTION_SERIALIZER =
            LegacyComponentSerializer.legacySection();
    private static final LegacyComponentSerializer AMPERSAND_SERIALIZER =
            LegacyComponentSerializer.legacyAmpersand();

    /**
     * 将消息转换为 Adventure Component。
     * 同时支持 § 和 & 颜色代码。
     */
    public static Component toComponent(String message) {
        if (message.indexOf('\u00A7') >= 0) {
            return SECTION_SERIALIZER.deserialize(message);
        }
        return AMPERSAND_SERIALIZER.deserialize(message);
    }

    // ===== GUI 专用方法：自动去除斜体 =====

    /**
     * GUI 物品显示名称，自动去除斜体。
     */
    public static Component guiName(String message) {
        return toComponent(message).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * GUI 物品显示名称（带占位符），自动去除斜体。
     */
    public static Component guiName(String message, String key, String value) {
        return toComponent(message.replace("{" + key + "}", value)).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * GUI 物品描述行，自动去除斜体。
     */
    public static Component guiLore(String message) {
        return toComponent(message).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * GUI 物品描述行（带占位符），自动去除斜体。
     */
    public static Component guiLore(String message, String key, String value) {
        return toComponent(message.replace("{" + key + "}", value)).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * GUI 物品描述行（带两个占位符），自动去除斜体。
     */
    public static Component guiLore(String message, String key1, String value1, String key2, String value2) {
        return toComponent(message
                .replace("{" + key1 + "}", value1)
                .replace("{" + key2 + "}", value2))
                .decoration(TextDecoration.ITALIC, false);
    }

    /**
     * 批量转换 GUI 描述行，自动去除斜体。
     */
    public static List<Component> guiLore(List<String> messages) {
        return messages.stream()
                .map(MessageUtil::guiLore)
                .toList();
    }

    public static void send(CommandSender sender, String message) {
        sender.sendMessage(toComponent(message));
    }

    public static void send(Player player, String message) {
        player.sendMessage(toComponent(message));
    }

    /**
     * 从任意线程安全地发送消息给 CommandSender。
     * 玩家通过 player.getScheduler() 调度到玩家线程，控制台直接发送。
     */
    public static void sendAsync(org.bukkit.command.CommandSender sender, String message, org.bukkit.plugin.Plugin plugin) {
        if (sender instanceof Player player) {
            player.getScheduler().execute(plugin, () -> player.sendMessage(toComponent(message)), () -> {}, 0L);
        } else {
            sender.sendMessage(toComponent(message));
        }
    }

    public static void broadcast(String message) {
        Bukkit.broadcast(toComponent(message));
    }
}