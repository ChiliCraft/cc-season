package com.chilicraft.season;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 动物迁徙与冬季清理任务：常规轮次把出生点半径内的名单动物随机迁往远处；
 * 入冬后前 N 天改为以玩家为中心按渐增半径软清理暖气候动物（粒子送别后移除），
 * 本轮不再迁徙，避免同轮对同一动物双重操作。
 */
final class AnimalMigrationTask implements Runnable {

    private final ChiliSeasonPlugin plugin;
    private final SeasonSettings settings;
    private final SeasonService service;

    AnimalMigrationTask(ChiliSeasonPlugin plugin, SeasonSettings settings, SeasonService service) {
        this.plugin = plugin;
        this.settings = settings;
        this.service = service;
    }

    @Override
    public void run() {
        if (!plugin.getServer().isPrimaryThread()) {
            plugin.getLogger().warning("动物迁徙任务误入异步线程，本轮已跳过");
            return;
        }
        if (!settings.migrationEnabled) {
            return;
        }
        // 入冬后前 winter-cleanup-days 天：只清理不迁徙
        if (settings.winterCleanupEnabled
                && service.state().season() == SeasonService.Season.WINTER
                && service.state().day() <= settings.winterCleanupDays) {
            performWinterCleanup();
            return;
        }
        for (World world : targetWorlds()) {
            migrateWorld(world);
        }
    }

    /** 目标世界：配置名单为空时视为全部世界。 */
    private List<World> targetWorlds() {
        if (settings.migrationWorlds.isEmpty()) {
            return new ArrayList<>(plugin.getServer().getWorlds());
        }
        List<World> worlds = new ArrayList<>();
        for (String worldName : settings.migrationWorlds) {
            World world = plugin.getServer().getWorld(worldName);
            if (world != null) {
                worlds.add(world);
            }
        }
        return worlds;
    }

    private void migrateWorld(World world) {
        Location spawn = world.getSpawnLocation();
        double radiusSquared = (double) settings.migrationRadius * settings.migrationRadius;
        int migrated = 0;
        for (Entity entity : world.getNearbyEntities(
                spawn, settings.migrationRadius, world.getMaxHeight() - world.getMinHeight(),
                settings.migrationRadius)) {
            if (migrated >= settings.migrationMax) {
                break;
            }
            try {
                if (!settings.animals.contains(entity.getType().name())) {
                    continue;
                }
                Location current = entity.getLocation();
                if (horizontalDistanceSquared(current, spawn) > radiusSquared) {
                    continue;
                }
                Location target = findSafeTarget(world, current);
                if (target != null && entity.teleport(target)) {
                    migrated++;
                    migrationEffect(current, target);
                }
            } catch (Throwable throwable) {
                plugin.getLogger().warning(
                        "动物迁徙处理失败（" + entity.getUniqueId() + "）：" + throwable.getMessage());
            }
        }
    }

    private void performWinterCleanup() {
        if (settings.winterCleanupMaxPerCycle <= 0) {
            return;
        }
        int dayIndex = Math.max(1, Math.min(service.state().day(), settings.winterCleanupDays));
        int radius = settings.winterCleanupBaseRadius
                + (dayIndex - 1) * settings.winterCleanupRadiusStep;
        double radiusSquared = (double) radius * radius;
        int budget = settings.winterCleanupMaxPerCycle;
        // 多玩家半径重叠时去重，同一实体一轮只处理一次
        Set<UUID> visited = new HashSet<>();
        for (World world : plugin.getServer().getWorlds()) {
            if (budget <= 0) {
                return;
            }
            if (world.getEnvironment() != World.Environment.NORMAL) {
                continue;
            }
            for (Player player : world.getPlayers()) {
                if (budget <= 0) {
                    return;
                }
                Location center = player.getLocation();
                for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
                    if (budget <= 0) {
                        return;
                    }
                    if (!(entity instanceof LivingEntity living)
                            || !living.isValid() || living.isDead()) {
                        continue;
                    }
                    String type = entity.getType().name();
                    // 仅清理暖气候动物，冷气候动物永不触碰
                    if (!settings.warmAnimals.contains(type)
                            || settings.coldAnimals.contains(type)) {
                        continue;
                    }
                    if (!visited.add(entity.getUniqueId())) {
                        continue;
                    }
                    if (entity.getLocation().distanceSquared(center) > radiusSquared) {
                        continue;
                    }
                    softRemove(entity.getLocation(), living);
                    budget--;
                }
            }
        }
    }

    /** 软淘汰：粒子送别后静默移除，营造「南迁过冬」的观感而非死亡。 */
    private void softRemove(Location location, LivingEntity entity) {
        if (settings.migrationParticles) {
            World world = location.getWorld();
            if (world != null) {
                world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE,
                        location.clone().add(0, 0.5, 0), 12, 0.3, 0.3, 0.3, 0.01);
            }
        }
        entity.remove();
    }

    /** 迁徙特效：起落点云雾粒子 + 落点传送音。 */
    private void migrationEffect(Location from, Location to) {
        if (!settings.migrationParticles) {
            return;
        }
        World world = from.getWorld();
        if (world == null) {
            return;
        }
        world.spawnParticle(Particle.CLOUD, from, 20, 0.5, 0.5, 0.5, 0.01);
        world.spawnParticle(Particle.CLOUD, to, 20, 0.5, 0.5, 0.5, 0.01);
        world.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.4f, 1.3f);
    }

    private Location findSafeTarget(World world, Location origin) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = random.nextDouble(Math.PI * 2.0);
            double distance = random.nextDouble(
                    settings.migrationMinDistance, settings.migrationMaxDistance + 1.0);
            int x = (int) Math.floor(origin.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(origin.getZ() + Math.sin(angle) * distance);
            int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            if (y <= world.getMinHeight() || y >= world.getMaxHeight()) {
                continue;
            }
            Location target = new Location(world, x + 0.5, y, z + 0.5, origin.getYaw(), origin.getPitch());
            if (target.getBlock().isPassable()
                    && target.clone().add(0, 1, 0).getBlock().isPassable()
                    && !target.clone().add(0, -1, 0).getBlock().isLiquid()) {
                return target;
            }
        }
        return null;
    }

    private double horizontalDistanceSquared(Location first, Location second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }
}
