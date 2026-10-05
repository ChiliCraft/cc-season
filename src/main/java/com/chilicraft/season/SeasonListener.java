package com.chilicraft.season;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 季节玩法监听器：作物季节速率、温室判定（屋顶 / 玻璃罩）、树苗成树、
 * 落叶快速腐烂、刷怪季相守卫、村民季节外观、手动天气回调与调试棒统计。
 * 高频路径（生长事件）先做廉价判断再进温室体积扫描。
 */
final class SeasonListener implements Listener {

    /** 参与快速腐烂的原木名单（与 SeasonCore 对齐）。 */
    private static final Set<Material> DECAY_LOGS = EnumSet.of(
            Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.DARK_OAK_LOG,
            Material.JUNGLE_LOG, Material.ACACIA_LOG, Material.CHERRY_LOG, Material.MANGROVE_LOG);

    /** 参与快速腐烂的树叶名单（含秋季视觉使用的金合欢叶）。 */
    private static final Set<Material> DECAY_LEAVES = EnumSet.of(
            Material.OAK_LEAVES, Material.SPRUCE_LEAVES, Material.BIRCH_LEAVES, Material.DARK_OAK_LEAVES,
            Material.JUNGLE_LEAVES, Material.ACACIA_LEAVES, Material.CHERRY_LEAVES, Material.MANGROVE_LEAVES);

    /** 豹猫允许自然生成的生境：只认真实 biome，发包伪装不改变真实 biome。 */
    private static final Set<Biome> OCELOT_HABITATS = EnumSet.of(
            Biome.JUNGLE, Biome.SPARSE_JUNGLE, Biome.BAMBOO_JUNGLE);

    private final ChiliSeasonPlugin plugin;
    private final SeasonSettings settings;
    private final SeasonService service;
    /** 手动天气回调目标；由插件在注册监听器之前创建，保证非空。 */
    private final SeasonTask task;
    /** 已看过首次进服指南的玩家（data/guides.yml 持久化，UUID 键避免 Player 泄漏）。 */
    private final Set<UUID> guideSeen = new HashSet<>();

    // 材料缓存：以 settings.version 为版本号，热载后重建，避免逐事件 matchMaterial
    private long cachedVersion = -1L;
    private Set<Material> glassMaterials = Set.of();
    private Material coreMaterial;
    private Material debugStick;

    SeasonListener(ChiliSeasonPlugin plugin, SeasonSettings settings,
                   SeasonService service, SeasonTask task) {
        this.plugin = plugin;
        this.settings = settings;
        this.service = service;
        this.task = task;
        loadGuideSeen();
    }

    // ---- 作物季节速率 ----

    @EventHandler(ignoreCancelled = true)
    public void grow(BlockGrowEvent event) {
        String crop = event.getBlock().getType().name().toLowerCase(Locale.ROOT);
        Map<String, Double> rates = settings.cropRates.get(crop);
        if (rates == null || rates.isEmpty()) {
            return; // 非名单作物：原版生长
        }
        SeasonService.Season season = service.state().season();
        if (greenhouse(event.getBlock())) {
            // 温室绕过季节限制与生长修正；冬季按 winter-bonus 倍率（1.0 = 与其他季节一致）
            applyRate(event, season == SeasonService.Season.WINTER ? settings.greenhouseWinterBonus : 1.0);
            return;
        }
        Double base = rates.get(season.name().toLowerCase(Locale.ROOT));
        if (base == null || base <= 0.0) {
            // 淡季（无条目或显式 0）：按 off-season-growth-chance 保留基础生长机会
            applyRate(event, settings.offSeasonChance);
            return;
        }
        applyRate(event, base * growthFactor(event.getBlock()));
    }

    /** 雨天加成 + 光照/地下修正，全部来自 crops.yml growth 段（温室内作物不走这里）。 */
    private double growthFactor(Block block) {
        double factor = 1.0;
        if (block.getWorld().hasStorm()) {
            factor *= settings.rainGrowthBonus;
        }
        int skyLight = block.getLightFromSky();
        if (skyLight <= 0) {
            factor *= settings.undergroundMultiplier;
        } else if (skyLight < settings.requiredLight) {
            factor *= settings.lowLightMultiplier;
        }
        return factor;
    }

    /**
     * 把速率落到事件上：&lt;1 按概率取消本次生长，&gt;1 有概率额外生长一级，
     * ≤0 直接取消——与 crops.yml 头注释语义一致。
     */
    private void applyRate(BlockGrowEvent event, double rate) {
        if (rate <= 0.0) {
            event.setCancelled(true);
            return;
        }
        if (rate < 1.0) {
            if (Math.random() > rate) {
                event.setCancelled(true);
            }
            return;
        }
        if (rate > 1.0 && Math.random() < Math.min(1.0, rate - 1.0)) {
            growExtraStage(event);
        }
    }

    private void growExtraStage(BlockGrowEvent event) {
        if (!(event.getNewState().getBlockData() instanceof Ageable ageable)) {
            return;
        }
        ageable.setAge(Math.min(ageable.getMaximumAge(), ageable.getAge() + 1));
        event.getNewState().setBlockData(ageable);
    }

    // ---- 树苗 / 真菌成树 ----

    /** 名单内树苗与真菌按季节速率决定能否成树；速率不作用于结构规模，只做放行 / 取消。 */
    @EventHandler(ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        Block source = event.getLocation().getBlock();
        Map<String, Double> rates =
                settings.cropRates.get(source.getType().name().toLowerCase(Locale.ROOT));
        if (rates == null || rates.isEmpty()) {
            return; // 非名单树苗：原版成树
        }
        if (greenhouse(source)) {
            return; // 温室内树苗不受季节限制
        }
        Double base = rates.get(service.state().season().name().toLowerCase(Locale.ROOT));
        double chance = (base == null || base <= 0.0)
                ? settings.offSeasonChance
                : Math.min(1.0, base);
        if (!(chance > 0.0) || Math.random() >= chance) {
            event.setCancelled(true);
        }
    }

    // ---- 温室判定（SeasonCore 语义） ----

    private boolean greenhouse(Block crop) {
        if (!settings.greenhouseEnabled) {
            return false;
        }
        GreenhouseStats stats = greenhouseStats(crop);
        return stats.roof()
                || (stats.glassCount() >= settings.greenhouseMinGlass
                    && (!settings.greenhouseCoreRequired || stats.coreFound()));
    }

    /** 温室判定统计：体积内玻璃计数、屋顶命中、核心方块命中。 */
    private record GreenhouseStats(int glassCount, boolean roof, boolean coreFound) {
    }

    private GreenhouseStats greenhouseStats(Block crop) {
        syncMaterialCaches();
        World world = crop.getWorld();
        int cx = crop.getX();
        int cy = crop.getY();
        int cz = crop.getZ();
        int radius = settings.greenhouseRadius;
        int maxY = Math.min(cy + settings.greenhouseMaxRoof, world.getMaxHeight() - 1);
        int glass = 0;
        boolean coreFound = false;
        // 提前退出：玻璃达标且核心条件满足即不再扫完整体积（户外作物的高频路径）
        outer:
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int y = cy + 1; y <= maxY; y++) {
                    Material mat = world.getBlockAt(cx + dx, y, cz + dz).getType();
                    if (mat.isAir()) {
                        continue;
                    }
                    if (glassMaterials.contains(mat)) {
                        glass++;
                    }
                    if (coreMaterial != null && mat == coreMaterial) {
                        coreFound = true;
                    }
                    if (glass >= settings.greenhouseMinGlass
                            && (!settings.greenhouseCoreRequired || coreFound)) {
                        break outer;
                    }
                }
            }
        }
        return new GreenhouseStats(glass, hasGlassRoof(crop), coreFound);
    }

    /**
     * 玻璃屋顶：作物同柱向上 max-roof-height+1 层内出现玻璃即命中；
     * 雪层不打断判定，其他固体打断（SeasonCore 同款语义）。
     */
    private boolean hasGlassRoof(Block crop) {
        World world = crop.getWorld();
        int startY = crop.getY() + 1;
        int maxY = Math.min(startY + settings.greenhouseMaxRoof, world.getMaxHeight() - 1);
        for (int y = startY; y <= maxY; y++) {
            Material mat = world.getBlockAt(crop.getX(), y, crop.getZ()).getType();
            if (mat.isAir()) {
                continue;
            }
            if (glassMaterials.contains(mat)) {
                return true;
            }
            if (mat == Material.SNOW || mat == Material.SNOW_BLOCK) {
                continue;
            }
            if (mat.isSolid()) {
                return false;
            }
        }
        return false;
    }

    // ---- 落叶快速腐烂 ----

    /** 原木被玩家破坏后延时扫描周边悬空树叶并加速消失（真实方块变更）。 */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!settings.fastLeafDecayEnabled) {
            return;
        }
        Block block = event.getBlock();
        if (!DECAY_LOGS.contains(block.getType())
                || settings.disabledWorlds.contains(block.getWorld().getName())) {
            return;
        }
        // 延时执行，等原版更新树叶距离后再判定悬空
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> decayLeavesAround(block), settings.fastLeafDecayDelay);
    }

    private void decayLeavesAround(Block origin) {
        World world = origin.getWorld();
        if (world == null || !world.isChunkLoaded(origin.getX() >> 4, origin.getZ() >> 4)) {
            return;
        }
        int radius = settings.fastLeafDecayRadius;
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        for (int x = ox - radius; x <= ox + radius; x++) {
            for (int y = oy - radius; y <= oy + radius; y++) {
                for (int z = oz - radius; z <= oz + radius; z++) {
                    // 逐方块守卫区块已加载，避免同步加载区块
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    Block leaf = world.getBlockAt(x, y, z);
                    if (!DECAY_LEAVES.contains(leaf.getType())) {
                        continue;
                    }
                    if (leaf.getBlockData() instanceof Leaves data && data.isPersistent()) {
                        continue; // 玩家放置的永久树叶不清理
                    }
                    if (hasLogNearby(leaf, Math.min(5, radius - 1))) {
                        continue; // 仍有原木连接：交给原版自然腐烂
                    }
                    if (Math.random() < settings.fastLeafDecayChance) {
                        leaf.breakNaturally(); // 按原版掉落
                    }
                }
            }
        }
    }

    private boolean hasLogNearby(Block leaf, int radius) {
        World world = leaf.getWorld();
        int lx = leaf.getX();
        int ly = leaf.getY();
        int lz = leaf.getZ();
        for (int x = lx - radius; x <= lx + radius; x++) {
            for (int y = ly - radius; y <= ly + radius; y++) {
                for (int z = lz - radius; z <= lz + radius; z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    if (DECAY_LOGS.contains(world.getBlockAt(x, y, z).getType())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ---- 刷怪季相守卫 + 村民季节外观 ----

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (settings.disabledWorlds.contains(event.getLocation().getWorld().getName())) {
            return;
        }
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        // 豹猫守卫：仅拦 NATURAL / CHUNK_GEN，真实 biome 非丛林生境即取消
        if (settings.spawnGuardEnabled
                && event.getEntityType() == EntityType.OCELOT
                && (reason == CreatureSpawnEvent.SpawnReason.NATURAL
                    || reason == CreatureSpawnEvent.SpawnReason.CHUNK_GEN)
                && !OCELOT_HABITATS.contains(event.getLocation().getBlock().getBiome())) {
            event.setCancelled(true);
            return;
        }
        // 换季生成加成与冬季暖气候淘汰：走自然生成路径，不干扰刷怪笼 / 繁殖等
        if (reason == CreatureSpawnEvent.SpawnReason.NATURAL
                || reason == CreatureSpawnEvent.SpawnReason.CHUNK_GEN
                || reason == CreatureSpawnEvent.SpawnReason.REINFORCEMENTS) {
            applySpawnBoost(event);
            applyWinterWarmCull(event);
            if (event.isCancelled()) {
                return;
            }
        }
        if (!settings.villagerEnabled || !(event.getEntity() instanceof Villager villager)) {
            return;
        }
        villager.setVillagerType(switch (service.state().season()) {
            case SPRING -> Villager.Type.PLAINS;
            case SUMMER -> Villager.Type.SAVANNA;
            case AUTUMN -> Villager.Type.TAIGA;
            case WINTER -> Villager.Type.SNOW;
        });
    }

    /**
     * 按季生成加成：名单内自然生成按概率在附近补刷一只（五成概率幼年），
     * 模拟随季节回迁；区块未加载时静默跳过，不为补刷同步加载区块。
     */
    private void applySpawnBoost(CreatureSpawnEvent event) {
        SeasonService.Season season = service.state().season();
        String seasonKey = season.name().toLowerCase(Locale.ROOT);
        Double chance = settings.spawnBoostChances.get(seasonKey);
        if (chance == null || chance <= 0.0) {
            return;
        }
        EntityType type = event.getEntityType();
        Set<String> names = settings.boostAnimals.get(seasonKey);
        if (names == null || !names.contains(type.name()) || Math.random() >= chance) {
            return;
        }
        World world = event.getLocation().getWorld();
        Location extra = event.getLocation().clone()
                .add((Math.random() * 2 - 1) * 3, 0, (Math.random() * 2 - 1) * 3);
        if (!world.isChunkLoaded(extra.getBlockX() >> 4, extra.getBlockZ() >> 4)) {
            return;
        }
        extra.setY(world.getHighestBlockYAt(extra, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1);
        Entity spawned = world.spawnEntity(extra, type);
        if (spawned instanceof org.bukkit.entity.Ageable ageable && Math.random() < 0.5) {
            ageable.setBaby();
        }
        if (settings.migrationParticles) {
            world.spawnParticle(Particle.HAPPY_VILLAGER, extra, 8, 0.5, 0.5, 0.5, 0.01);
        }
    }

    /** 冬季自然生成暖气候动物按概率取消（软淘汰）；冷气候动物不受影响。 */
    private void applyWinterWarmCull(CreatureSpawnEvent event) {
        if (service.state().season() != SeasonService.Season.WINTER
                || settings.softDespawnChance <= 0.0) {
            return;
        }
        String type = event.getEntityType().name();
        if (!settings.warmAnimals.contains(type) || settings.coldAnimals.contains(type)) {
            return;
        }
        if (Math.random() < settings.softDespawnChance) {
            event.setCancelled(true);
        }
    }

    // ---- 日历推进 ----

    @EventHandler(ignoreCancelled = true)
    public void sleep(TimeSkipEvent event) {
        if (event.getSkipReason() == TimeSkipEvent.SkipReason.NIGHT_SKIP) {
            service.advanceFromSleep(event.getWorld());
        }
    }

    // ---- 手动天气回调 ----

    /** 玩家手动 /weather 后通知主循环跳过下一次自动排期（weather.respect-manual-commands）。 */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        markManualWeather(event.getMessage());
    }

    /** 控制台 / 命令方块手动天气同样尊重。 */
    @EventHandler(ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        markManualWeather(event.getCommand());
    }

    private void markManualWeather(String raw) {
        if (task == null || !settings.respectManualCommands || raw == null || raw.isEmpty()) {
            return;
        }
        String line = raw.charAt(0) == '/' ? raw.substring(1) : raw;
        line = line.trim();
        int space = line.indexOf(' ');
        String token = space >= 0 ? line.substring(0, space) : line;
        int colon = token.indexOf(':'); // 去掉 minecraft: 命名空间前缀
        if (colon >= 0) {
            token = token.substring(colon + 1);
        }
        token = token.toLowerCase(Locale.ROOT);
        if (token.equals("weather") || token.equals("toggledownfall")) {
            task.markManualWeather();
        }
    }

    // ---- 首次进服指南 ----

    /**
     * 首次进服按配置自动发放季节指南书：先落盘记录再延迟发放，
     * 防断线重连或发放时机异常导致重复发书（SeasonCore 同款语义）。
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!settings.guideOnFirstJoin) {
            return;
        }
        UUID id = event.getPlayer().getUniqueId();
        if (!guideSeen.add(id)) {
            return;
        }
        saveGuideSeen();
        // 延迟约 3 秒等物品栏就绪；发放时再校验在线，离线则静默跳过
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null && player.isOnline()) {
                player.getInventory().addItem(new SeasonGuide(settings).create());
            }
        }, 60L);
    }

    /** 读取历史发放记录；非法 UUID 条目静默跳过。 */
    private void loadGuideSeen() {
        File file = new File(plugin.getDataFolder(), "data/guides.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String raw : yaml.getStringList("seen")) {
            try {
                guideSeen.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                // 非法条目跳过，下次进服会重新发放并覆盖
            }
        }
    }

    /** 同步保存发放记录（主线程事件内调用，与 calendar.yml 同范式）。 */
    private void saveGuideSeen() {
        File file = new File(plugin.getDataFolder(), "data/guides.yml");
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("无法创建指南发放记录目录：" + parent);
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("seen", guideSeen.stream().map(UUID::toString).toList());
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("保存指南发放记录失败：" + e.getMessage());
        }
    }

    // ---- 调试棒 ----

    /** 手持调试棒右键名单作物：输出温室判定统计（玻璃数 / 屋顶 / 核心 / 结果）。 */
    @EventHandler(ignoreCancelled = true)
    public void onDebugStick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null) {
            return;
        }
        if (!settings.greenhouseEnabled) {
            return;
        }
        syncMaterialCaches();
        ItemStack item = event.getItem();
        if (debugStick == null || item == null || item.getType() != debugStick) {
            return;
        }
        Block crop = event.getClickedBlock();
        if (!settings.cropRates.containsKey(crop.getType().name().toLowerCase(Locale.ROOT))) {
            return;
        }
        GreenhouseStats stats = greenhouseStats(crop);
        boolean protectedCrop = stats.roof()
                || (stats.glassCount() >= settings.greenhouseMinGlass
                    && (!settings.greenhouseCoreRequired || stats.coreFound()));
        event.getPlayer().sendMessage(SeasonMessages.render(settings, "command.greenhouse-debug",
                "<gray>温室检测：玻璃 %glass% 块 · 屋顶 %roof% · 核心 %core% · %result%</gray>",
                "%glass%", String.valueOf(stats.glassCount()),
                "%roof%", stats.roof() ? "是" : "否",
                "%core%", settings.greenhouseCoreRequired ? (stats.coreFound() ? "是" : "否") : "不要求",
                "%result%", protectedCrop ? "已受温室保护" : "未受温室保护"));
    }

    // ---- 材料缓存 ----

    /** 按 settings.version 重建玻璃 / 核心 / 调试棒材料缓存；版本未变时直接返回。 */
    private void syncMaterialCaches() {
        if (cachedVersion == settings.version) {
            return;
        }
        cachedVersion = settings.version;
        Set<Material> glasses = EnumSet.noneOf(Material.class);
        for (String name : settings.greenhouseGlassTypes) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                glasses.add(material);
            }
        }
        glassMaterials = glasses;
        coreMaterial = settings.greenhouseCoreRequired
                ? Material.matchMaterial(settings.greenhouseCoreBlock)
                : null;
        debugStick = Material.matchMaterial(settings.greenhouseDebugStick);
    }
}
