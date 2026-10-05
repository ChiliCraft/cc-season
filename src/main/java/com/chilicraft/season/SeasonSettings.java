package com.chilicraft.season;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 插件配置。{@link #refresh()} / {@link #loadCrops} 整体重建，非法值回退默认并告警。
 */
final class SeasonSettings {

    // 日历
    int daysPerSeason = 28;
    int realMinutes = 0;
    boolean followWorld = false;
    boolean advanceSleep = false;
    boolean requirePlayers = false;
    int maxCatchup = 2;
    String overworld = "world";
    int startYear = 1;
    int startDay = 1;
    SeasonService.Season startSeason = SeasonService.Season.SPRING;

    // 主循环与迁徙
    int mainPeriodSeconds = 1;
    boolean migrationEnabled = false;
    int migrationPeriod = 45;
    int migrationMax = 20;
    int migrationRadius = 260;
    int migrationMinDistance = 40;
    int migrationMaxDistance = 80;
    Set<String> migrationWorlds = Set.of();
    Set<String> animals = Set.of();

    // 天气排期（按季排雨天数 + 雷暴概率，替代每次随机掷阈）
    boolean weatherEnabled = true;
    Map<String, Integer> rainyDays = Map.of();
    int rainyDaysDefault = 5;
    double thunderChance = 0.2;
    Set<String> weatherDisabledWorlds = Set.of();

    // HUD
    boolean hudEnabled = true;
    String hudTitle = "";
    Map<String, String> hudColors = Map.of();

    // 季节视觉（仅发包，不修改真实 biome/方块）
    boolean visualEnabled = true;
    boolean resend = true;
    int refreshBatchSize = 64;
    Set<String> disabledWorlds = Set.of();
    Map<String, String> visualBiomes = Map.of();
    // 秋叶显示替换：源材料 → 目标材料（小块名），仅 seasons 列出的季节生效
    boolean foliageEnabled = true;
    List<String> foliageSeasons = List.of("autumn");
    Map<String, String> foliageMap = Map.of();
    // 季节植被显示替换：季节 →（源材料 → 目标材料）
    boolean floraEnabled = true;
    Map<String, Map<String, String>> floraSeasons = Map.of();

    // 季节粒子（客户端本地生成，不写世界）
    boolean particlesEnabled = true;
    Map<String, ParticleRule> particleRules = Map.of();

    // 作物与生长修正
    Map<String, Map<String, Double>> cropRates = Map.of();
    boolean greenhouseEnabled = true;
    int greenhouseAbove = 3;
    double rainGrowthBonus = 1.0;
    double offSeasonChance = 0.1;
    int requiredLight = 4;
    double lowLightMultiplier = 0.7;
    double undergroundMultiplier = 0.8;

    // 村民季节外观
    boolean villagerEnabled = true;

    // 指南书与消息
    List<String> guideLines = List.of();
    // messages 段：含嵌套键（如 command.hud-on），缺失时返回空串
    Map<String, String> messages = Map.of();

    /**
     * 配置版本号：refresh/loadCrops 每次调用 +1。
     * 派生状态（如天气排期）据此判断是否需要按新配置重建。
     */
    long version = 0;

    private final JavaPlugin plugin;

    /** 季节粒子规则：density 为每秒每玩家粒子数（0.06 ≈ 每玩家每秒 6 个）。 */
    record ParticleRule(Particle particle, double density, int radius) {
    }

    SeasonSettings(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** 读取 messages 段（含嵌套键）；缺失返回空串，调用方回退默认文案。 */
    String message(String key) {
        return messages.getOrDefault(key, "");
    }

    void refresh() {
        this.version++;
        FileConfiguration config = plugin.getConfig();
        this.daysPerSeason = positive(config.getInt("calendar.days-per-season", 28), 28, "calendar.days-per-season");
        this.realMinutes = nonNegative(config.getInt("calendar.real-time-minutes-per-day", 0), 0, "calendar.real-time-minutes-per-day");
        this.followWorld = config.getBoolean("calendar.follow-overworld-time", false);
        this.advanceSleep = config.getBoolean("calendar.advance-on-sleep", false);
        this.requirePlayers = config.getBoolean("calendar.require-players", false);
        this.maxCatchup = nonNegative(config.getInt("calendar.max-catchup", 2), 2, "calendar.max-catchup");
        this.overworld = config.getString("calendar.overworld", "world");
        this.startYear = Math.max(1, config.getInt("calendar.start-year", 1));
        this.startDay = clampInt(config.getInt("calendar.start-day", 1), 1, this.daysPerSeason, 1, "calendar.start-day");
        this.startSeason = parseSeason(config.getString("calendar.start-season", "spring"), SeasonService.Season.SPRING);
        this.mainPeriodSeconds = positive(config.getInt("weather.check-seconds", 60), 60, "weather.check-seconds");
        this.weatherEnabled = config.getBoolean("weather.enabled", true);
        this.rainyDays = loadDayCounts(config.getConfigurationSection("weather.rainy-days"));
        this.rainyDaysDefault = nonNegative(config.getInt("weather.rainy-days-per-season", 5), 5, "weather.rainy-days-per-season");
        this.thunderChance = ratio(config.getDouble("weather.thunder-chance", 0.2), 0.2, "weather.thunder-chance");
        this.weatherDisabledWorlds = Set.copyOf(config.getStringList("weather.disabled-worlds"));
        this.migrationEnabled = config.getBoolean("migration.enabled", true);
        this.migrationPeriod = positive(config.getInt("migration.interval-seconds", 45), 45, "migration.interval-seconds");
        this.migrationMax = positive(config.getInt("migration.per-cycle", 20), 20, "migration.per-cycle");
        this.migrationRadius = positive(config.getInt("migration.radius", 260), 260, "migration.radius");
        this.migrationMinDistance = nonNegative(config.getInt("migration.min-distance", 40), 40, "migration.min-distance");
        this.migrationMaxDistance = nonNegative(config.getInt("migration.max-distance", 80), 80, "migration.max-distance");
        this.migrationWorlds = Set.copyOf(config.getStringList("migration.worlds"));
        this.animals = Set.copyOf(config.getStringList("migration.animals"));
        this.hudEnabled = config.getBoolean("hud.enabled", true);
        this.hudTitle = config.getString("hud.title", "");
        this.hudColors = loadStrings(config.getConfigurationSection("hud.colors"));
        this.visualEnabled = config.getBoolean("visual.enabled", true);
        this.resend = config.getBoolean("visual.resend-on-season-change", true);
        this.refreshBatchSize = positive(config.getInt("visual.refresh-batch-size", 64), 64, "visual.refresh-batch-size");
        this.disabledWorlds = Set.copyOf(config.getStringList("visual.disabled-worlds"));
        this.visualBiomes = loadVisuals(config.getConfigurationSection("visual.mappings"));
        this.foliageEnabled = config.getBoolean("visual.foliage.enabled", true);
        List<String> foliageSeasonList = normalizeSeasons(config.getStringList("visual.foliage.seasons"));
        this.foliageSeasons = foliageSeasonList.isEmpty() ? List.of("autumn") : foliageSeasonList;
        this.foliageMap = loadMaterials(config.getConfigurationSection("visual.foliage.mappings"));
        this.floraEnabled = config.getBoolean("visual.flora.enabled", true);
        this.floraSeasons = loadSubstitutions(config.getConfigurationSection("visual.flora.seasons"));
        this.particlesEnabled = config.getBoolean("particles.enabled", true);
        this.particleRules = loadParticles(config.getConfigurationSection("particles.seasons"));
        this.villagerEnabled = config.getBoolean("villagers.enabled", true);
        this.guideLines = List.copyOf(config.getStringList("messages.guide-lines"));
        this.messages = loadStrings(config.getConfigurationSection("messages"));
    }

    /** 重新加载 crops.yml：作物季节速率、温室与生长修正。 */
    void loadCrops(FileConfiguration crops) {
        this.version++;
        this.cropRates = loadWeights(crops);
        this.greenhouseEnabled = crops.getBoolean("greenhouse.enabled", true);
        this.greenhouseAbove = nonNegative(crops.getInt("greenhouse.glass-above", 3), 3, "greenhouse.glass-above");
        this.rainGrowthBonus = ratio(crops.getDouble("growth.rain-growth-bonus", 1.0), 1.0, "growth.rain-growth-bonus");
        this.offSeasonChance = ratio(crops.getDouble("growth.off-season-growth-chance", 0.10), 0.10, "growth.off-season-growth-chance");
        this.requiredLight = clampInt(crops.getInt("growth.required-light", 4), 0, 15, 4, "growth.required-light");
        this.lowLightMultiplier = ratio(crops.getDouble("growth.low-light-multiplier", 0.70), 0.70, "growth.low-light-multiplier");
        this.undergroundMultiplier = ratio(crops.getDouble("growth.underground-multiplier", 0.80), 0.80, "growth.underground-multiplier");
    }

    private int positive(int value, int fallback, String path) {
        if (value > 0) {
            return value;
        }
        plugin.getLogger().warning("配置 " + path + " 非法（" + value + "），已回退默认值 " + fallback + "。");
        return fallback;
    }

    private int nonNegative(int value, int fallback, String path) {
        if (value >= 0) {
            return value;
        }
        plugin.getLogger().warning("配置 " + path + " 非法（" + value + "），已回退默认值 " + fallback + "。");
        return fallback;
    }

    private double ratio(double value, double fallback, String path) {
        if (Double.isFinite(value) && value >= 0.0) {
            return value;
        }
        plugin.getLogger().warning("配置 " + path + " 非法（" + value + "），已回退默认值 " + fallback + "。");
        return fallback;
    }

    private int clampInt(int value, int min, int max, int fallback, String path) {
        if (value < min || value > max) {
            plugin.getLogger().warning("配置 " + path + " 超出范围 [" + min + "," + max + "]（" + value + "），已回退默认值 " + fallback + "。");
            return fallback;
        }
        return value;
    }

    private SeasonService.Season parseSeason(String raw, SeasonService.Season fallback) {
        try {
            return SeasonService.Season.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("配置 calendar.start-season 非法（" + raw + "），已回退默认值 " + fallback.name().toLowerCase(Locale.ROOT) + "。");
            return fallback;
        }
    }

    /** 季节名规范化：仅保留合法季节，小写去重。 */
    private List<String> normalizeSeasons(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String value : raw) {
            String season = value.trim().toLowerCase(Locale.ROOT);
            if ((season.equals("spring") || season.equals("summer") || season.equals("autumn") || season.equals("winter"))
                    && !out.contains(season)) {
                out.add(season);
            }
        }
        return List.copyOf(out);
    }

    private Map<String, String> loadStrings(ConfigurationSection section) {
        Map<String, String> out = new HashMap<>();
        if (section != null) {
            for (String key : section.getKeys(true)) {
                out.put(key, String.valueOf(section.get(key)));
            }
        }
        return Map.copyOf(out);
    }

    private Map<String, String> loadVisuals(ConfigurationSection section) {
        Map<String, String> out = new HashMap<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String biome = section.getString(key, "");
                if (!biome.isEmpty()) {
                    out.put(key.toLowerCase(Locale.ROOT), biome);
                }
            }
        }
        return Map.copyOf(out);
    }

    /**
     * 校验材料映射：键与值都必须是合法材料名，非法项告警后跳过。
     * 全部小写归一，供发包重映射使用。
     */
    private Map<String, String> loadMaterials(ConfigurationSection section) {
        Map<String, String> out = new HashMap<>();
        if (section == null) {
            return Map.of();
        }
        for (String key : section.getKeys(false)) {
            String source = key.toLowerCase(Locale.ROOT);
            String target = String.valueOf(section.get(key)).toLowerCase(Locale.ROOT);
            if (Material.matchMaterial(source) == null || Material.matchMaterial(target) == null) {
                plugin.getLogger().warning("方块视觉映射存在未知材料：" + source + " -> " + target + "，已跳过。");
                continue;
            }
            out.put(source, target);
        }
        return Map.copyOf(out);
    }

    private Map<String, Map<String, String>> loadSubstitutions(ConfigurationSection root) {
        Map<String, Map<String, String>> out = new HashMap<>();
        if (root == null) {
            return Map.of();
        }
        for (String season : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(season);
            if (section == null) {
                continue;
            }
            Map<String, String> row = loadMaterials(section);
            if (!row.isEmpty()) {
                out.put(season.toLowerCase(Locale.ROOT), row);
            }
        }
        return Map.copyOf(out);
    }

    private Map<String, ParticleRule> loadParticles(ConfigurationSection section) {
        Map<String, ParticleRule> out = new HashMap<>();
        if (section == null) {
            return Map.of();
        }
        for (String season : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(season);
            if (entry == null || !entry.getBoolean("enabled", false)) {
                continue;
            }
            String name = String.valueOf(entry.get("particle", "SNOWFLAKE")).toUpperCase(Locale.ROOT);
            Particle particle;
            try {
                particle = Particle.valueOf(name);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("配置 particles.seasons." + season + ".particle 无效：" + name + "，该季节粒子已跳过。");
                continue;
            }
            double density = ratio(entry.getDouble("density", 0.05), 0.05, "particles.seasons." + season + ".density");
            int radius = positive(entry.getInt("radius", 10), 10, "particles.seasons." + season + ".radius");
            out.put(season.toLowerCase(Locale.ROOT), new ParticleRule(particle, density, radius));
        }
        return Map.copyOf(out);
    }

    private Map<String, Map<String, Double>> loadWeights(FileConfiguration crops) {
        Map<String, Map<String, Double>> out = new HashMap<>();
        ConfigurationSection section = crops.getConfigurationSection("crops");
        if (section != null) {
            for (String crop : section.getKeys(false)) {
                ConfigurationSection inner = section.getConfigurationSection(crop);
                if (inner == null) {
                    continue;
                }
                Map<String, Double> weights = new HashMap<>();
                for (String season : inner.getKeys(false)) {
                    try {
                        weights.put(season.toLowerCase(Locale.ROOT), inner.getDouble(season, 0.0));
                    } catch (RuntimeException exception) {
                        plugin.getLogger().warning("crops.yml 作物 " + crop + " 的 " + season + " 权重非法，已按 0 处理。");
                        weights.put(season.toLowerCase(Locale.ROOT), 0.0);
                    }
                }
                if (!weights.isEmpty()) {
                    out.put(crop.toLowerCase(Locale.ROOT), Map.copyOf(weights));
                }
            }
        }
        return Map.copyOf(out);
    }

    private Map<String, Integer> loadDayCounts(ConfigurationSection section) {
        Map<String, Integer> out = new HashMap<>();
        if (section == null) {
            return Map.of();
        }
        for (String key : section.getKeys(false)) {
            String season = key.trim().toLowerCase(Locale.ROOT);
            if (normalizeSeasons(List.of(season)).isEmpty()) {
                plugin.getLogger().warning("配置 weather.rainy-days 存在非法季节名：" + key + "，已跳过。");
                continue;
            }
            out.put(season, nonNegative(section.getInt(key, 0), 0, "weather.rainy-days." + key));
        }
        return Map.copyOf(out);
    }
}
