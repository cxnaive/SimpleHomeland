package dev.user.homeland.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自定义家园设置，存储为 JSON 字符串到数据库。
 * 键名对应 rules.yml 中的 gamerule key（如 "PREVENT_ICE_SNOW_MELTING"）。
 */
public class CustomSettings {

    private final Map<String, Boolean> settings;

    public CustomSettings() {
        this.settings = new LinkedHashMap<>();
    }

    private CustomSettings(Map<String, Boolean> settings) {
        this.settings = settings;
    }

    /**
     * 从 JSON 字符串解析。null 或空字符串返回空设置。
     */
    public static CustomSettings fromJson(String json) {
        CustomSettings result = new CustomSettings();
        if (json == null || json.isBlank()) return result;

        try {
            String content = json.trim();
            if (content.startsWith("{")) content = content.substring(1);
            if (content.endsWith("}")) content = content.substring(0, content.length() - 1);

            String[] pairs = content.split(",");
            for (String pair : pairs) {
                String[] kv = pair.split(":", 2);
                if (kv.length != 2) continue;
                String key = kv[0].trim().replace("\"", "");
                boolean value = Boolean.parseBoolean(kv[1].trim());
                result.settings.put(key, value);
            }
        } catch (Exception e) {
            // 解析失败，使用空设置
        }
        return result;
    }

    /**
     * 序列化为 JSON 字符串。
     */
    public String toJson() {
        if (settings.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Boolean> entry : settings.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 获取某个设置的值。
     */
    public boolean get(String key, boolean defaultValue) {
        return settings.getOrDefault(key, defaultValue);
    }

    /**
     * 设置某个设置的值。
     */
    public void set(String key, boolean value) {
        settings.put(key, value);
    }
}
