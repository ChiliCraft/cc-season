package com.chilicraft.season;

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

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    /** 粒子采样时相对玩家的垂直扰动上限，避免读取未加载区块。 */
    private static final int PARTICLE_VERTICAL_SPREAD = 3;

    private final ChiliSeasonPlugin plugin;
    private final SeasonSettings settings;
    private final SeasonService service;
    private final BossBar bar;
    private final Set<UUID> hidden;
    private int weatherTicks;
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
                if (hidden.contains(player.getUniqueId())) {
                    bar.removePlayer(player);
                } else {
                    bar.addPlayer(player);
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("HUD 更新失败：" + exception.getMessage());
            }
        }
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

    /** 周期性套用当日计划天气；计划尚未构建（首日以前）时视为晴天。 */
    private void applyWeather() {
        if (dayPlan.isEmpty()) {
            return;
        }
        DayWeather today = dayPlan.getOrDefault(service.state().day(), DayWeather.CLEAR);
        boolean storm = today != DayWeather.CLEAR;
        boolean thundering = today == DayWeather.THUNDER;
        for (World world : Bukkit.getWorlds()) {
            if (settings.weatherDisabledWorlds.contains(world.getName())) {
                continue;
            }
            if (world.hasStorm() != storm || world.isThundering() != thundering) {
                world.setStorm(storm);
                world.setThundering(thundering);
            }
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
        dayPlan = Map.of();
        planSeason = null;
        planVersion = -1L;
    }
}
