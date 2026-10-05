package com.chilicraft.season;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.entity.Villager;

import java.util.Locale;
import java.util.Map;

final class SeasonListener implements Listener {

    private final SeasonSettings settings;
    private final SeasonService service;

    SeasonListener(SeasonSettings settings, SeasonService service) {
        this.settings = settings;
        this.service = service;
    }

    @EventHandler(ignoreCancelled = true)
    public void grow(BlockGrowEvent event) {
        String crop = event.getBlock().getType().name().toLowerCase(Locale.ROOT);
        Map<String, Double> rates = settings.cropRates.get(crop);
        if (rates == null || greenhouse(event.getBlock())) {
            return;
        }
        String season = service.state().season().name().toLowerCase(Locale.ROOT);
        Double base = rates.get(season);
        if (base == null) {
            return;
        }
        if (base <= 0.0) {
            // 淡季：不直接判死刑，按 off-season-growth-chance 保留基础生长机会
            event.setCancelled(!(settings.offSeasonChance > 0.0 && Math.random() < settings.offSeasonChance));
            return;
        }
        double rate = base * growthFactor(event.getBlock());
        if (rate < 1.0 && Math.random() > rate) {
            event.setCancelled(true);
            return;
        }
        if (rate > 1.0 && Math.random() < Math.min(1.0, rate - 1.0)) {
            growExtraStage(event);
        }
    }

    /** 雨天加成 + 光照/地下修正，全部来自 crops.yml growth 段。 */
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

    private void growExtraStage(BlockGrowEvent event) {
        if (!(event.getNewState().getBlockData() instanceof Ageable ageable)) {
            return;
        }
        ageable.setAge(Math.min(ageable.getMaximumAge(), ageable.getAge() + 1));
        event.getNewState().setBlockData(ageable);
    }

    @EventHandler(ignoreCancelled = true)
    public void sleep(TimeSkipEvent event) {
        if (event.getSkipReason() == TimeSkipEvent.SkipReason.NIGHT_SKIP) {
            service.advanceFromSleep(event.getWorld());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void villagerSpawn(CreatureSpawnEvent event) {
        if (!settings.villagerEnabled
                || settings.disabledWorlds.contains(event.getLocation().getWorld().getName())
                || !(event.getEntity() instanceof Villager villager)) {
            return;
        }
        villager.setVillagerType(switch (service.state().season()) {
            case SPRING -> Villager.Type.PLAINS;
            case SUMMER -> Villager.Type.SAVANNA;
            case AUTUMN -> Villager.Type.TAIGA;
            case WINTER -> Villager.Type.SNOW;
        });
    }

    private boolean greenhouse(Block block) {
        if (!settings.greenhouseEnabled) {
            return false;
        }
        for (int offset = 1; offset <= settings.greenhouseAbove; offset++) {
            if (block.getRelative(0, offset, 0).getType() != Material.GLASS) {
                return false;
            }
        }
        return true;
    }
}
