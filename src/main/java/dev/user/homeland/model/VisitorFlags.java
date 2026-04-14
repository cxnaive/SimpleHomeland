package dev.user.homeland.model;

import java.util.EnumMap;

public class VisitorFlags {

    private final EnumMap<VisitorFlag, Boolean> flags;

    public VisitorFlags() {
        this.flags = new EnumMap<>(VisitorFlag.class);
        for (VisitorFlag flag : VisitorFlag.values()) {
            flags.put(flag, flag.getDefaultValue());
        }
    }

    private VisitorFlags(EnumMap<VisitorFlag, Boolean> flags) {
        this.flags = flags;
    }

    /**
     * 从 JSON 字符串解析。null 或空字符串返回所有默认值。
     */
    public static VisitorFlags fromJson(String json) {
        VisitorFlags result = new VisitorFlags();
        if (json == null || json.isBlank()) return result;

        try {
            // 简单 JSON 解析，不依赖外部库
            String content = json.trim();
            if (content.startsWith("{")) content = content.substring(1);
            if (content.endsWith("}")) content = content.substring(0, content.length() - 1);

            String[] pairs = content.split(",");
            for (String pair : pairs) {
                String[] kv = pair.split(":", 2);
                if (kv.length != 2) continue;
                String key = kv[0].trim().replace("\"", "");
                boolean value = Boolean.parseBoolean(kv[1].trim());
                VisitorFlag flag = VisitorFlag.fromKey(key);
                if (flag != null) {
                    result.flags.put(flag, value);
                }
            }
        } catch (Exception e) {
            // 解析失败，使用默认值
        }
        return result;
    }

    /**
     * 序列化为 JSON 字符串。
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        VisitorFlag[] values = VisitorFlag.values();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(",");
            VisitorFlag flag = values[i];
            sb.append("\"").append(flag.getKey()).append("\":").append(flags.get(flag));
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 获取某个 flag 的值。
     */
    public boolean get(VisitorFlag flag) {
        return flags.getOrDefault(flag, flag.getDefaultValue());
    }

    /**
     * 设置某个 flag 的值。
     */
    public void set(VisitorFlag flag, boolean value) {
        flags.put(flag, value);
    }
}
