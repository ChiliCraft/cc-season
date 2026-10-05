package com.chilicraft.season;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

final class SeasonTask implements Runnable {

    /** 单日天气计划类型：排期决定，全天稳定。 */
    private enum DayWeather {
        CLEAR,
        RAIN,
        THUNDER
    }

    /** 逐玩家 HUD 模式：FIXED 固定显示 / VARIABLE 名单显示 / OFF 隐藏。 */
    private enum HudMode {
        FIXED,
        VARIABLE,
        OFF
    }

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    /** 粒子采样时相对玩家的垂直扰动上限，避免读取未加载区块。 */
    private static final int PARTICLE_VERTICAL_SPREAD = 3;

    private final ChiliSeasonPlugin plugin;
    private final SeasonSettings settings;
    private final SeasonService service;
    private final BossBar bar;
    private final Set<UUID> hidden;
    /** 每位玩家最近一次发送的 ActionBar 文本，用于 VARIABLE 模式的标题变更检测。 */
    private final Map<UUID, String> lastActionbarText = new HashMap<>();
    /** ActionBar 当前可见玩家，隐藏切换时据此只清一次栏。 */
    private final Set<UUID> actionbarShown = new HashSet<>();
    private int weatherTicks;
    /** 手动天气豁免：监听器捕获手动改天命令后置位，跳过下一次自动排期。 */
    private boolean manualOverride;
    /** 上一次见到的日序，跨日时重置手动豁免（新的一天按新计划执行）。 */
    private int lastSeenDay = -1;
    /** 本季（本年）逐日天气计划：季次日 -> 天气。随换季 / 配置版本变化重建。 */
    private Map<Integer, DayWeather> dayPlan = Map.of();
    private String planSeason;
    private int planYear;
    private int planDays;
    private long planVersion = -1L;

    SeasonTask(ChiliSeasonPlugin plugin, SeasonSettings settings,
               SeasonService service, Set<UUID> hidden) {
        this.plugin = plugin;
        this.settings = settings;
        this.service = service;
        this.hidden = hidden;
        this.bar = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID);
    }

    @Override
    public void run() {
        service.tick();
        int day = service.state().day();
        if (day != lastSeenDay) {
            lastSeenDay = day;
            manualOverride = false;
        }
        if (settings.weatherEnabled) {
            refreshWeatherPlan();
            weatherTicks++;
            if (weatherTicks >= settings.mainPeriodSeconds) {
                weatherTicks = 0;
                applyWeather();
            }
        } else {
            dayPlan = Map.of();
            planSeason = null;
            planVersion = -1L;
        }
        spawnSeasonParticles();
        updateHud();
    }

    private void updateHud() {
        if (!settings.hudEnabled) {
            bar.removeAll();
            clearActionbars();
            return;
        }
        SeasonService.CalendarState state = service.state();
        String season = state.season().name().toLowerCase(Locale.ROOT);
        String title = settings.hudTitle
                .replace("%year%", String.valueOf(state.year()))
                .replace("%season%", season)
                .replace("%day%", String.valueOf(state.day()));
        try {
            bar.setTitle(LEGACY.serialize(MINI.deserialize(title)));
        } catch (RuntimeException exception) {
            bar.setTitle(title);
        }
        bar.setColor(colorOf(state.season()));
        bar.setProgress(Math.min(1.0, Math.max(0.01,
                (double) state.day() / settings.daysPerSeason)));
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                HudMode mode = modeOf(player.getUniqueId());
                if (mode == HudMode.OFF) {
                    bar.removePlayer(player);
                } else {
                    bar.addPlayer(player);
                }
                updateActionbar(player, title, mode);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("HUD 更新失败：" + exception.getMessage());
            }
        }
    }

    /** 逐玩家模式裁决：个人隐藏（/ccseason hud）> off-players 名单 > variable-players 名单 > 默认模式。 */
    private HudMode modeOf(UUID playerId) {
        if (hidden.contains(playerId) || settings.hudOffPlayers.contains(playerId)) {
            return HudMode.OFF;
        }
        if (settings.hudVariablePlayers.contains(playerId)) {
            return HudMode.VARIABLE;
        }
        return switch (settings.hudDefaultMode) {
            case "VARIABLE" -> HudMode.VARIABLE;
            case "OFF" -> HudMode.OFF;
            default -> HudMode.FIXED;
        };
    }

    /**
     * ActionBar 策略：FIXED 每周期重发保持常显；VARIABLE 仅在标题变化时发一次
     * （显示数秒后自然淡出）；OFF 时按 clear-on-hide 决定是否清栏，只清一次。
     */
    private void updateActionbar(Player player, String title, HudMode mode) {
        if (!settings.hudActionBarEnabled) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (mode == HudMode.OFF) {
            if (actionbarShown.remove(playerId)) {
                lastActionbarText.remove(playerId);
                if (settings.hudActionBarClearOnHide) {
                    player.sendActionBar(Component.empty());
                }
            }
            return;
        }
        if (mode == HudMode.VARIABLE && title.equals(lastActionbarText.get(playerId))) {
            return;
        }
        Component text;
        try {
            text = MINI.deserialize(title);
        } catch (RuntimeException exception) {
            text = Component.text(title);
        }
        player.sendActionBar(text);
        lastActionbarText.put(playerId, title);
        actionbarShown.add(playerId);
    }

    /** HUD 整体关闭或插件禁用时清空 ActionBar 缓存，并按配置清一次可见栏。 */
    private void clearActionbars() {
        if (settings.hudActionBarClearOnHide) {
            for (UUID playerId : actionbarShown) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    player.sendActionBar(Component.empty());
                }
            }
        }
        actionbarShown.clear();
        lastActionbarText.clear();
    }

    private BarColor colorOf(SeasonService.Season season) {
        BarColor fallback = switch (season) {
            case SPRING -> BarColor.PINK;
            case SUMMER -> BarColor.YELLOW;
            case AUTUMN -> BarColor.RED;
            case WINTER -> BarColor.BLUE;
        };
        String configured = settings.hudColors.get(season.name().toLowerCase(Locale.ROOT));
        if (configured == null) {
            return fallback;
        }
        try {
            return BarColor.valueOf(configured.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    // ---- 天气按季排期 ----

    /** 换季、跨年或配置变化时重建当日天气计划。 */
    private void refreshWeatherPlan() {
        SeasonService.CalendarState state = service.state();
        String season = state.season().name().toLowerCase(Locale.ROOT);
        if (season.equals(planSeason)
                && state.year() == planYear
                && settings.daysPerSeason == planDays
                && settings.version == planVersion) {
            return;
        }
        buildPlan(state, season);
    }

    /**
     * 按 weather.rainy-days 从本季天数中等概抽取降雨日，降雨日再按
     * thunder-chance 决定是否雷暴，其余为晴天。计划生成后全天稳定。
     */
    private void buildPlan(SeasonService.CalendarState state, String season) {
        int days = settings.daysPerSeason;
        int rainy = Math.min(days,
                Math.max(0, settings.rainyDays.getOrDefault(season, settings.rainyDaysDefault)));
        List<Integer> pool = new ArrayList<>(days);
        for (int day = 1; day <= days; day++) {
            pool.add(day);
        }
        Collections.shuffle(pool, ThreadLocalRandom.current());
        Map<Integer, DayWeather> plan = new HashMap<>();
        for (int index = 0; index < rainy; index++) {
            int day = pool.get(index);
            plan.put(day, ThreadLocalRandom.current().nextDouble() < settings.thunderChance
                    ? DayWeather.THUNDER
                    : DayWeather.RAIN);
        }
        dayPlan = plan;
        planSeason = season;
        planYear = state.year();
        planDays = days;
        planVersion = settings.version;
    }

    /**
     * 监听器捕获到玩家 / 控制台 / 命令方块手动改天（weather、toggledownfall）时调用：
     * 跳过下一次自动排期，尊重管理员手动操作，之后的周期恢复按计划覆盖。
     */
    void markManualWeather() {
        manualOverride = true;
    }

    /** 周期性套用当日计划天气；手动豁免时跳过一次；计划尚未构建（首日以前）时不动。 */
    private void applyWeather() {
        if (dayPlan.isEmpty()) {
            return;
        }
        if (manualOverride) {
            manualOverride = false;
            return;
        }
        DayWeather today = dayPlan.getOrDefault(service.state().day(), DayWeather.CLEAR);
        boolean storm = today != DayWeather.CLEAR;
        boolean thundering = today == DayWeather.THUNDER;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int duration = storm
                ? random.nextInt(settings.stormMinTicks, settings.stormMaxTicks + 1)
                : random.nextInt(settings.clearMinTicks, settings.clearMaxTicks + 1);
        for (World world : Bukkit.getWorlds()) {
            if (settings.weatherDisabledWorlds.contains(world.getName())) {
                continue;
            }
            if (world.hasStorm() != storm || world.isThundering() != thundering) {
                world.setStorm(storm);
                world.setThundering(thundering);
            }
            // 晴天时该值即距下次自然变天的时间，雨天时为剩余雨时，均实现「时长区间」
            world.setWeatherDuration(duration);
        }
    }

    // ---- 季节粒子（仅客户端视觉，不写世界） ----

    /**
     * 每秒逐玩家生成当前季节粒子。density 为每秒每玩家粒子数 ×100，
     * 例如 0.06 表示每玩家每秒 6 个；整数部分固定生成，小数部分按概率补 1 个。
     */
    private void spawnSeasonParticles() {
        if (!settings.particlesEnabled) {
            return;
        }
        SeasonSettings.ParticleRule rule =
                settings.particleRules.get(service.state().season().name().toLowerCase(Locale.ROOT));
        if (rule == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                spawnForPlayer(player, rule);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("季节粒子生成失败：" + exception.getMessage());
            }
        }
    }

    private void spawnForPlayer(Player player, SeasonSettings.ParticleRule rule) {
        double perSecond = rule.density() * 100.0;
        int count = (int) perSecond;
        if (ThreadLocalRandom.current().nextDouble() < perSecond - count) {
            count++;
        }
        if (count <= 0) {
            return;
        }
        World world = player.getWorld();
        if (settings.disabledWorlds.contains(world.getName())) {
            return;
        }
        Location center = player.getLocation();
        int radius = rule.radius();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int index = 0; index < count; index++) {
            int x = (int) center.getX() + random.nextInt(-radius, radius + 1);
            int z = (int) center.getZ() + random.nextInt(-radius, radius + 1);
            int y = clampToWorld(world, center.getBlockY()
                    + random.nextInt(-PARTICLE_VERTICAL_SPREAD, PARTICLE_VERTICAL_SPREAD + 1));
            if (!world.getBlockAt(x, y, z).isEmpty()) {
                continue;
            }
            player.spawnParticle(rule.particle(), x, y + 0.5, z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private int clampToWorld(World world, int y) {
        return Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 1, y));
    }

    void clear() {
        bar.removeAll();
        clearActionbars();
        dayPlan = Map.of();
        planSeason = null;
        planVersion = -1L;
    }
}
