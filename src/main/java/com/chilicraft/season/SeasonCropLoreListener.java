package com.chilicraft.season;

import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 作物图鉴：给名单内作物的种子/物品追加「适合季节」「温室光照要求」lore。
 * 内容完全由 crops.yml 与配置文案决定，不随当前季节变化，因此仅在物品出现、
 * 被拾取、来自战利品或玩家进服时按 PDC 幂等刷新。
 */
final class SeasonCropLoreListener implements Listener {

    private static final List<String> SEASON_ORDER = List.of("spring", "summer", "autumn", "winter");
    private static final Map<String, String> FALLBACK_NAMES = Map.of(
            "spring", "春", "summer", "夏", "autumn", "秋", "winter", "冬");

    /** 物品材料 -> crops.yml 作物键（小写）。 */
    private static final Map<org.bukkit.Material, String> ITEM_TO_CROP = Map.ofEntries(
            Map.entry(org.bukkit.Material.WHEAT_SEEDS, "wheat"),
            Map.entry(org.bukkit.Material.BEETROOT_SEEDS, "beetroots"),
            Map.entry(org.bukkit.Material.CARROT, "carrots"),
            Map.entry(org.bukkit.Material.POTATO, "potatoes"),
            Map.entry(org.bukkit.Material.MELON_SEEDS, "melon_stem"),
            Map.entry(org.bukkit.Material.PUMPKIN_SEEDS, "pumpkin_stem"),
            Map.entry(org.bukkit.Material.TORCHFLOWER_SEEDS, "torchflower_crop"),
            Map.entry(org.bukkit.Material.PITCHER_POD, "pitcher_crop"),
            Map.entry(org.bukkit.Material.SUGAR_CANE, "sugar_cane"),
            Map.entry(org.bukkit.Material.CACTUS, "cactus"),
            Map.entry(org.bukkit.Material.BAMBOO, "bamboo"),
            Map.entry(org.bukkit.Material.SWEET_BERRIES, "sweet_berry_bush"),
            Map.entry(org.bukkit.Material.NETHER_WART, "nether_wart"),
            Map.entry(org.bukkit.Material.COCOA_BEANS, "cocoa"));

    private final ChiliSeasonPlugin plugin;
    private final SeasonSettings settings;
    private final NamespacedKey countKey;
    private final NamespacedKey signatureKey;

    SeasonCropLoreListener(ChiliSeasonPlugin plugin, SeasonSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.countKey = new NamespacedKey(plugin, "crop_lore_count");
        this.signatureKey = new NamespacedKey(plugin, "crop_lore_sig");
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!settings.cropLoreEnabled) {
            return;
        }
        Item entity = event.getEntity();
        if (ITEM_TO_CROP.containsKey(entity.getItemStack().getType())) {
            apply(entity.getItemStack()).ifPresent(entity::setItemStack);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!settings.cropLoreEnabled || !(event.getEntity() instanceof Player)) {
            return;
        }
        Item entity = event.getItem();
        if (ITEM_TO_CROP.containsKey(entity.getItemStack().getType())) {
            apply(entity.getItemStack()).ifPresent(entity::setItemStack);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLoot(LootGenerateEvent event) {
        if (!settings.cropLoreEnabled) {
            return;
        }
        for (ItemStack stack : event.getLoot()) {
            apply(stack);
        }
    }

    /** 进服时刷新库存，补齐插件上线前获得或配置变更前写入的物品。 */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!settings.cropLoreEnabled) {
            return;
        }
        Player player = event.getPlayer();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && ITEM_TO_CROP.containsKey(stack.getType())
                    && apply(stack).isPresent()) {
                inventory.setItem(slot, stack);
            }
        }
        ItemStack offhand = inventory.getItemInOffHand();
        if (ITEM_TO_CROP.containsKey(offhand.getType()) && apply(offhand).isPresent()) {
            inventory.setItemInOffHand(offhand);
        }
    }

    /**
     * 幂等追加图鉴 lore。签名一致时跳过；签名不同（crops.yml 热载后）时
     * 按 PDC 记录的行数从 lore 末尾移除旧行再重写——本插件追加的行始终位于末尾。
     *
     * @return 有改写时返回新物品栈，无改动返回空
     */
    private java.util.Optional<ItemStack> apply(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return java.util.Optional.empty();
        }
        String crop = ITEM_TO_CROP.get(stack.getType());
        if (crop == null) {
            return java.util.Optional.empty();
        }
        Map<String, Double> rates = settings.cropRates.get(crop);
        if (rates == null || rates.isEmpty()) {
            return java.util.Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return java.util.Optional.empty();
        }
        PersistentDataContainer view = meta.getPersistentDataContainer();
        String signature = signature(rates);
        Integer applied = view.get(countKey, PersistentDataType.INTEGER);
        if (signature.equals(view.get(signatureKey, PersistentDataType.STRING))) {
            return java.util.Optional.empty();
        }
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        if (applied != null && applied > 0 && lore.size() >= applied) {
            lore.subList(lore.size() - applied, lore.size()).clear();
        }
        List<Component> lines = new ArrayList<>();
        List<String> seasons = new ArrayList<>();
        for (String season : SEASON_ORDER) {
            if (rates.getOrDefault(season, 0.0) > 0.0) {
                seasons.add(settings.cropLoreSeasonNames.getOrDefault(season,
                        FALLBACK_NAMES.getOrDefault(season, season)));
            }
        }
        if (seasons.size() >= SEASON_ORDER.size()) {
            lines.add(SeasonMessages.render(settings, "crop-lore.any-season",
                    "<gray>适合季节：<green>四季皆可</green></gray>"));
        } else {
            lines.add(SeasonMessages.render(settings, "crop-lore.grows-in",
                    "<gray>适合季节：<green>%seasons%</green></gray>",
                    "%seasons%", String.join("、", seasons)));
        }
        lines.add(SeasonMessages.render(settings, "crop-lore.greenhouse",
                "<dark_gray>温室遮顶 + 光照 %light%+</dark_gray>",
                "%light%", String.valueOf(settings.requiredLight)));
        lore.addAll(lines);
        meta.lore(lore);
        view.set(countKey, PersistentDataType.INTEGER, lines.size());
        view.set(signatureKey, PersistentDataType.STRING, signature);
        stack.setItemMeta(meta);
        return java.util.Optional.of(stack);
    }

    /** 当前配置下的图鉴签名：任一作物季节速率变化都视为过期。 */
    private String signature(Map<String, Double> rates) {
        StringBuilder builder = new StringBuilder(24);
        for (String season : SEASON_ORDER) {
            builder.append(season, 0, Math.min(2, season.length()))
                    .append('=').append(rates.getOrDefault(season, 0.0).toString().toLowerCase(Locale.ROOT))
                    .append(';');
        }
        return builder.toString();
    }
}
