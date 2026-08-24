package dev.user.homeland.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自定义家园设置，存储为 JSON 字符串到数据库。
 * 键名对应 rules.yml 中的 gamerule key（如 "PREVENT_ICE_SNOW_MELTING"）。
 */
public class CustomSettings {

    private volatile Map<String, Boolean> settings;
    private final Map<String, Long> keyVersions = new LinkedHashMap<>();
    private long nextVersion;

    public record Update(String key, Boolean previousValue, long version) {}

    public CustomSettings() {
        this.settings = Map.of();
    }

    /**
     * 从 JSON 字符串解析。null 或空字符串返回空设置。
     */
    public static CustomSettings fromJson(String json) {
        CustomSettings result = new CustomSettings();
        if (json == null || json.isBlank()) return result;

        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            Map<String, Boolean> parsed = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                    parsed.put(entry.getKey(), value.getAsBoolean());
                }
            }
            result.settings = Collections.unmodifiableMap(parsed);
        } catch (RuntimeException ignored) {
            // 解析失败，使用空设置
        }
        return result;
    }

    public static String updateJson(String json, String key, boolean value) {
        CustomSettings settings = fromJson(json);
        settings.set(key, value);
        return settings.toJson();
    }

    /**
     * 序列化为 JSON 字符串。
     */
    public String toJson() {
        Map<String, Boolean> snapshot = settings;
        JsonObject object = new JsonObject();
        for (Map.Entry<String, Boolean> entry : snapshot.entrySet()) {
            object.addProperty(entry.getKey(), entry.getValue());
        }
        return object.toString();
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
    public synchronized void set(String key, boolean value) {
        update(key, value);
    }

    public synchronized Update update(String key, boolean value) {
        Boolean previousValue = settings.get(key);
        Map<String, Boolean> updated = new LinkedHashMap<>(settings);
        updated.put(key, value);
        settings = Collections.unmodifiableMap(updated);
        long version = ++nextVersion;
        keyVersions.put(key, version);
        return new Update(key, previousValue, version);
    }

    public synchronized boolean rollback(Update update) {
        if (!Long.valueOf(update.version()).equals(keyVersions.get(update.key()))) return false;

        Map<String, Boolean> restored = new LinkedHashMap<>(settings);
        if (update.previousValue() == null) {
            restored.remove(update.key());
        } else {
            restored.put(update.key(), update.previousValue());
        }
        settings = Collections.unmodifiableMap(restored);
        keyVersions.put(update.key(), ++nextVersion);
        return true;
    }
}
