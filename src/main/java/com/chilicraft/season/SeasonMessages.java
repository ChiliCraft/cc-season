package com.chilicraft.season;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * 面向玩家消息的统一出口：模板来自 config {@code messages} 段，缺失回退默认文案，
 * MiniMessage 解析失败回退纯文本——与项目消息范式一致。
 */
final class SeasonMessages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private SeasonMessages() {
    }

    /**
     * @param placeholders 成对的「占位符, 替换值」，如 {@code "%year%", "3"}；无变量时传空
     */
    static Component render(SeasonSettings settings, String key, String fallback, String... placeholders) {
        String template = settings.message(key);
        if (template.isEmpty()) {
            template = fallback;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            template = template.replace(placeholders[i], placeholders[i + 1]);
        }
        try {
            return MINI.deserialize(template);
        } catch (RuntimeException ignored) {
            return Component.text(template);
        }
    }
}
