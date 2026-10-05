package com.chilicraft.season;

import com.chilicraft.core.gui.GuiHolder;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class SeasonGui {
    private final SeasonService service;
    private final SeasonGuide guide;
    private final SeasonClockService clock;
    private final SeasonSettings settings;
    private final Set<UUID> hidden;

    SeasonGui(SeasonService service, SeasonSettings settings, Set<UUID> hidden) {
        this.service = service;
        this.settings = settings;
        this.hidden = hidden;
        this.guide = new SeasonGuide(settings);
        this.clock = new SeasonClockService();
    }

    void open(Player player) {
        SeasonService.CalendarState state = service.state();
        String season = state.season().name().toLowerCase(Locale.ROOT);
        GuiHolder holder = new GuiHolder(27,
                SeasonMessages.render(settings, "menu-title", "季节菜单"));
        holder.set(10, button(Material.SUNFLOWER,
                SeasonMessages.render(settings, "gui.status", "季节状态"),
                List.of(SeasonMessages.render(settings, "gui.status-lore",
                        "第%year%年 · %season% · 第%day%天",
                        "%year%", String.valueOf(state.year()),
                        "%season%", season,
                        "%day%", String.valueOf(state.day())))));
        holder.set(12, button(Material.GLOWSTONE_DUST,
                SeasonMessages.render(settings, "gui.hud", "切换 HUD"),
                List.of(SeasonMessages.render(settings, "gui.hud-lore", "点击切换季节 HUD"))),
                (p, type) -> toggleHud(p));
        holder.set(14, button(Material.WRITABLE_BOOK,
                SeasonMessages.render(settings, "gui.guide", "季节指南"),
                List.of(SeasonMessages.render(settings, "gui.guide-lore", "获得当前季节指南"))),
                (p, type) -> p.getInventory().addItem(guide.create()));
        holder.set(16, button(Material.CLOCK,
                SeasonMessages.render(settings, "gui.clock", "季节时钟"),
                List.of(SeasonMessages.render(settings, "gui.clock-lore", "获得当前季节时钟"))),
                (p, type) -> p.getInventory().addItem(clock.create(service.state())));
        holder.set(22, button(Material.BARRIER,
                SeasonMessages.render(settings, "gui.close", "关闭"), List.of()),
                (p, type) -> p.closeInventory());
        holder.open(player);
    }

    private void toggleHud(Player player) {
        if (hidden.remove(player.getUniqueId())) {
            player.sendMessage(SeasonMessages.render(settings, "command.hud-on", "季节 HUD 已开启。"));
        } else {
            hidden.add(player.getUniqueId());
            player.sendMessage(SeasonMessages.render(settings, "command.hud-off", "季节 HUD 已关闭。"));
        }
    }

    private ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
