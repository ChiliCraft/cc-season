package com.chilicraft.season;

import com.chilicraft.api.ModuleCommandExecutor;
import com.chilicraft.api.ModuleTabCompleter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class SeasonCommand implements CommandExecutor, TabCompleter, ModuleCommandExecutor, ModuleTabCompleter {
    static final String USE_PERMISSION = "chilicraft.season.use";
    static final String ADMIN_PERMISSION = "chilicraft.season.admin";
    static final String LEGACY_ADMIN_PERMISSION = "ccseason.admin";
    private final SeasonService service;
    private final SeasonSettings settings;
    private final SeasonGuide guide;
    private final SeasonClockService clock;
    private final SeasonGui gui;
    private final Set<UUID> hidden;

    SeasonCommand(SeasonService service, SeasonSettings settings, Set<UUID> hidden, SeasonGui gui) {
        this.service = service;
        this.settings = settings;
        this.hidden = hidden;
        this.gui = gui;
        this.guide = new SeasonGuide(settings);
        this.clock = new SeasonClockService();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return execute(sender, args);
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(USE_PERMISSION) && !isAdmin(sender)) {
            send(sender, "command.permission", "你没有使用季节命令的权限。");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            if (sender instanceof Player player) gui.open(player);
            else send(sender, "command.menu-players-only", "季节菜单只能由玩家打开。");
            return true;
        }
        if (args[0].equalsIgnoreCase("info")) {
            sendInfo(sender);
            return true;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "command.players-only", "该操作只能由玩家执行。");
            return true;
        }
        if (args[0].equalsIgnoreCase("hud")) {
            toggleHud(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("guide")) {
            player.getInventory().addItem(guide.create());
            return true;
        }
        if (args[0].equalsIgnoreCase("clock")) {
            player.getInventory().addItem(clock.create(service.state()));
            return true;
        }
        if (args[0].equalsIgnoreCase("set")) return setDate(player, args);
        send(sender, "command.usage", "用法：/ccseason [menu|info|hud|guide|clock|set]");
        return true;
    }

    /** 发送 messages 段消息：缺失回退默认文案，MiniMessage 解析失败回退纯文本。 */
    private void send(CommandSender sender, String key, String fallback, String... placeholders) {
        sender.sendMessage(SeasonMessages.render(settings, key, fallback, placeholders));
    }

    private void sendInfo(CommandSender sender) {
        SeasonService.CalendarState state = service.state();
        send(sender, "command.info", "第%year%年 %season% 第%day%天",
                "%year%", String.valueOf(state.year()),
                "%season%", state.season().name().toLowerCase(Locale.ROOT),
                "%day%", String.valueOf(state.day()));
    }

    private void toggleHud(Player player) {
        if (hidden.remove(player.getUniqueId())) {
            send(player, "command.hud-on", "季节 HUD 已开启。");
        } else {
            hidden.add(player.getUniqueId());
            send(player, "command.hud-off", "季节 HUD 已关闭。");
        }
    }

    private boolean setDate(Player player, String[] args) {
        if (!isAdmin(player)) {
            send(player, "command.no-permission", "你没有权限。");
            return true;
        }
        if (args.length < 3) {
            send(player, "command.set-usage", "用法：/ccseason set season|day <值>");
            return true;
        }
        if (args[1].equalsIgnoreCase("day")) {
            try {
                int day = Integer.parseInt(args[2]);
                if (day < 1 || day > settings.daysPerSeason) {
                    send(player, "command.day-range", "日期必须在 1 到 %days% 之间。",
                            "%days%", String.valueOf(settings.daysPerSeason));
                    return true;
                }
                service.setDayFromCommand(day);
                send(player, "command.day-set", "季节日期已调整。");
            } catch (NumberFormatException exception) {
                send(player, "command.day-invalid", "日期必须是数字。");
            }
            return true;
        }
        if (args[1].equalsIgnoreCase("season")) {
            SeasonService.Season season = SeasonService.Season.parseOrNull(args[2]);
            if (season == null) {
                send(player, "command.season-invalid", "季节必须是 spring、summer、autumn 或 winter。");
                return true;
            }
            service.setSeasonFromCommand(season);
            send(player, "command.season-set", "季节已调整。");
            return true;
        }
        send(player, "command.set-usage", "用法：/ccseason set season|day <值>");
        return true;
    }

    private boolean isAdmin(CommandSender sender) {
        return sender.hasPermission(ADMIN_PERMISSION) || sender.hasPermission(LEGACY_ADMIN_PERMISSION);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return complete(sender, args);
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(USE_PERMISSION) && !isAdmin(sender)) return List.of();
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("menu", "info", "hud", "guide", "clock"));
            if (isAdmin(sender)) values.add("set");
            return filter(values, args[0]);
        }
        if (isAdmin(sender) && args.length == 2 && args[0].equalsIgnoreCase("set")) {
            return filter(List.of("season", "day"), args[1]);
        }
        if (isAdmin(sender) && args.length == 3 && args[0].equalsIgnoreCase("set")
                && args[1].equalsIgnoreCase("season")) {
            return filter(List.of("spring", "summer", "autumn", "winter"), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> values, String input) {
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT)
                .startsWith(input.toLowerCase(Locale.ROOT))).toList();
    }
}
