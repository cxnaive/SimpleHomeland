package dev.user.homeland.placeholder;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.model.Homeland;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

public class HomelandExpansion extends PlaceholderExpansion {

    private final SimpleHomelandPlugin plugin;

    public HomelandExpansion(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "simplehomeland";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";

        String param = params.toLowerCase();
        List<Homeland> homelands = plugin.getHomelandManager().getHomelands(player.getUniqueId());

        // 在线玩家缓存未命中时尝试从缓存索引获取，避免同步阻塞主线程
        // 离线玩家缓存未命中时返回空列表，PAPI 变量将显示为空值

        if (param.equals("count")) {
            return String.valueOf(homelands.size());
        }

        if (param.equals("max")) {
            return String.valueOf(plugin.getConfigManager().getMaxHomelands());
        }

        if (param.equals("list")) {
            if (homelands.isEmpty()) return "";
            return homelands.stream()
                    .map(Homeland::getName)
                    .collect(Collectors.joining(", "));
        }

        if (param.equals("has_homeland")) {
            return homelands.isEmpty() ? "false" : "true";
        }

        // %simplehomeland_border_<名称>%
        if (param.startsWith("border_")) {
            String name = param.substring(7);
            return homelands.stream()
                    .filter(h -> h.getName().equalsIgnoreCase(name))
                    .findFirst()
                    .map(h -> String.valueOf(h.getBorderRadius()))
                    .orElse("0");
        }

        // %simplehomeland_has_nether_<名称>%
        if (param.startsWith("has_nether_")) {
            String name = param.substring(11);
            return homelands.stream()
                    .filter(h -> h.getName().equalsIgnoreCase(name))
                    .findFirst()
                    .map(h -> h.hasNether() ? "true" : "false")
                    .orElse("false");
        }

        // %simplehomeland_has_end_<名称>%
        if (param.startsWith("has_end_")) {
            String name = param.substring(8);
            return homelands.stream()
                    .filter(h -> h.getName().equalsIgnoreCase(name))
                    .findFirst()
                    .map(h -> h.hasEnd() ? "true" : "false")
                    .orElse("false");
        }

        // %simplehomeland_is_public_<名称>%
        if (param.startsWith("is_public_")) {
            String name = param.substring(10);
            return homelands.stream()
                    .filter(h -> h.getName().equalsIgnoreCase(name))
                    .findFirst()
                    .map(h -> h.isPublic() ? "true" : "false")
                    .orElse("false");
        }

        // %simplehomeland_difficulty_<名称>% — 指定家园的难度
        if (param.startsWith("difficulty_")) {
            String name = param.substring(11);
            return homelands.stream()
                    .filter(h -> h.getName().equalsIgnoreCase(name))
                    .findFirst()
                    .map(h -> {
                        World w = plugin.getHomelandManager().getHomelandWorld(h.getWorldKey());
                        return w != null ? w.getDifficulty().name().toLowerCase() : "";
                    })
                    .orElse("");
        }

        // %simplehomeland_current_owner% — 当前所在世界的家园主人名字
        if (param.equals("current_owner")) {
            if (!(player instanceof org.bukkit.entity.Player onlinePlayer)) return "";
            World world = onlinePlayer.getWorld();
            String worldKey = world.getKey().getKey();

            // 去除维度后缀以获取主世界 key
            String baseKey = worldKey;
            if (worldKey.endsWith("_nether")) baseKey = worldKey.substring(0, worldKey.length() - 7);
            else if (worldKey.endsWith("_the_end")) baseKey = worldKey.substring(0, worldKey.length() - 8);

            Homeland homeland = plugin.getHomelandManager().getHomelandByWorldKey(baseKey);
            if (homeland == null) {
                return plugin.getConfigManager().getMessage("papi-current-owner-none");
            }

            String ownerName = plugin.getServer().getOfflinePlayer(homeland.getOwnerUuid()).getName();
            return ownerName != null ? ownerName : homeland.getOwnerUuid().toString().substring(0, 8);
        }

        // %simplehomeland_current% — 当前所在世界信息
        if (param.equals("current")) {
            if (!(player instanceof org.bukkit.entity.Player onlinePlayer)) return "";
            World world = onlinePlayer.getWorld();
            String worldKey = world.getKey().getKey();

            Homeland homeland = plugin.getHomelandManager().getHomelandByWorldKey(worldKey);
            if (homeland == null) {
                // 非家园世界
                return plugin.getConfigManager().getMessage("papi-current-main-world");
            }

            // 获取家园主人名称
            String ownerName = plugin.getServer().getOfflinePlayer(homeland.getOwnerUuid()).getName();
            if (ownerName == null) ownerName = homeland.getOwnerUuid().toString().substring(0, 8);

            String baseWorldKey = homeland.getWorldKey();
            if (worldKey.equals(baseWorldKey)) {
                return plugin.getConfigManager().getMessage("papi-current-overworld",
                        "owner", ownerName, "name", homeland.getName());
            } else if (worldKey.equals(baseWorldKey + "_nether")) {
                return plugin.getConfigManager().getMessage("papi-current-nether",
                        "owner", ownerName, "name", homeland.getName());
            } else if (worldKey.equals(baseWorldKey + "_the_end")) {
                return plugin.getConfigManager().getMessage("papi-current-end",
                        "owner", ownerName, "name", homeland.getName());
            }
            return "";
        }

        return null;
    }
}