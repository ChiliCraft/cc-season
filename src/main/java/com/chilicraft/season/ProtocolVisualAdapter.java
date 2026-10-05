package com.chilicraft.season;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.WrappedLevelChunkData;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 使用 ProtocolLib 仅修改玩家收到的区块 biome/block palette，不改变服务器真实区块。 */
final class ProtocolVisualAdapter {

    private final ChiliSeasonPlugin plugin;
    private final SeasonService service;
    private final SeasonSettings settings;
    private PacketAdapter listener;
    private BukkitTask refreshTask;
    private boolean available;
    /**
     * 每季 biome 显示重映射（原全局 palette ID → 显示全局 palette ID）。
     * reload 在主线程局部构建后整体替换引用，发包线程只读快照，跨线程无需加锁。
     */
    private volatile Map<String, Map<Integer, Integer>> biomeRemaps = Map.of();
    /** 每季方块状态 ID 映射（秋叶/植被显示替换），与 biome 重写随同一发发包；可见性语义同上。 */
    private volatile Map<String, Map<Integer, Integer>> seasonBlocks = Map.of();
    private final ArrayDeque<RefreshTarget> refreshQueue = new ArrayDeque<>();
    private long rewritten;
    private long skipped;
    private long failed;
    private long refreshedChunks;
    private long refreshNanos;

    ProtocolVisualAdapter(ChiliSeasonPlugin plugin, SeasonService service, SeasonSettings settings) {
        this.plugin = plugin;
        this.service = service;
        this.settings = settings;
        Plugin protocolLib = plugin.getServer().getPluginManager().getPlugin("ProtocolLib");
        if (protocolLib == null || !protocolLib.isEnabled() || !settings.visualEnabled) {
            if (settings.visualEnabled) {
                plugin.getLogger().warning("未检测到 ProtocolLib，季节视觉适配已禁用");
            }
            return;
        }
        try {
            listener = new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.MAP_CHUNK) {
                @Override
                public void onPacketSending(PacketEvent event) {
                    rewrite(event);
                }
            };
            ProtocolLibrary.getProtocolManager().addPacketListener(listener);
            available = true;
            reload();
            plugin.getLogger().info("已启用逐玩家季节 biome 发包视觉");
        } catch (Throwable throwable) {
            plugin.getLogger().warning("ProtocolLib biome 视觉初始化失败，已降级：" + throwable.getMessage());
        }
    }

    private void rewrite(PacketEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();
        if (settings.disabledWorlds.contains(world.getName())) {
            return;
        }
        String season = service.state().season().name().toLowerCase(Locale.ROOT);
        Map<Integer, Integer> blockMap = seasonBlocks.getOrDefault(season, Map.of());
        Map<Integer, Integer> biomeMap = biomeRemaps.getOrDefault(season, Map.of());
        if (blockMap.isEmpty() && biomeMap.isEmpty()) {
            return;
        }
        try {
            StructureModifier<WrappedLevelChunkData.ChunkData> modifier =
                    event.getPacket().getLevelChunkData();
            if (modifier.size() == 0) {
                skipped++;
                return;
            }
            WrappedLevelChunkData.ChunkData data = modifier.read(0);
            byte[] rewritten = rewriteSections(data.getBuffer(), world, biomeMap, blockMap);
            if (rewritten != null) {
                data.setBuffer(rewritten);
                modifier.write(0, data);
                this.rewritten++;
            } else {
                skipped++;
            }
        } catch (Throwable throwable) {
            failed++;
        }
    }

    /** 反射读取服务器 biome registry 全表（小写名 → 全局 palette ID），供逐家族构建映射，失败抛 IOException。 */
    private Map<String, Integer> loadRegistryBiomes() throws IOException {
        try {
            Class<?> craftWorld = Class.forName("org.bukkit.craftbukkit.CraftWorld");
            Method getHandle = craftWorld.getMethod("getHandle");
            Object handle = getHandle.invoke(Bukkit.getWorlds().get(0));
            Object registryAccess = findNoArgMethod(handle.getClass(), "registryAccess").invoke(handle);

            Class<?> registriesClass = Class.forName("net.minecraft.core.registries.Registries");
            Object biomeRegistryKey = registriesClass.getField("BIOME").get(null);
            Method registry = findOneArgMethod(
                    registryAccess.getClass(), "registryOrThrow", biomeRegistryKey.getClass());
            Object biomeRegistry = registry.invoke(registryAccess, biomeRegistryKey);

            Set<?> locations = (Set<?>) findNoArgMethod(biomeRegistry.getClass(), "keySet").invoke(biomeRegistry);
            Class<?> resourceLocationClass = Class.forName("net.minecraft.resources.ResourceLocation");
            Method get = findOneArgMethod(biomeRegistry.getClass(), "get", resourceLocationClass);

            // getId 需以实际 biome 类型解析一次后全表复用
            Object sample = null;
            for (Object location : locations) {
                sample = get.invoke(biomeRegistry, location);
                if (sample != null) {
                    break;
                }
            }
            if (sample == null) {
                throw new IOException("biome registry 全表为空");
            }
            Method getId = findRegistryIdMethod(biomeRegistry.getClass(), sample.getClass());

            Map<String, Integer> names = new HashMap<>();
            for (Object location : locations) {
                Object biome = get.invoke(biomeRegistry, location);
                if (biome == null) {
                    continue;
                }
                names.put(normalizeName(location.toString()),
                        ((Number) getId.invoke(biomeRegistry, biome)).intValue());
            }
            return Map.copyOf(names);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IOException("无法读取服务器 biome registry 全表", exception);
        }
    }

    /** registry key（如 minecraft:plains）→ 配置风格小写名（去命名空间前缀）。 */
    private static String normalizeName(String key) {
        int colon = key.indexOf(':');
        String name = colon >= 0 ? key.substring(colon + 1) : key;
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * biome 家族分类（与配置风格小写名匹配）：海洋优先于岸滩（浅海变体名含 shore），
     * 再判河流，其余归陆地。
     */
    private static String familyOf(String name) {
        if (name.contains("ocean")) {
            return "OCEAN";
        }
        if (name.contains("beach") || name.contains("shore")) {
            return "SHORE";
        }
        if (name.contains("river")) {
            return "RIVER";
        }
        return "LAND";
    }

    /**
     * 解析 ocean 显示目标（deep 变体保留规则）：
     * 原 deep 区域 → 尝试 DEEP_目标变体（registry 缺失回退目标）；
     * 原浅水区域而目标是 DEEP_ → 去 DEEP_ 前缀（缺失回退目标）。
     * keep-deep-variants 关闭时 deep 区域也直接显示浅水目标。
     */
    private static String resolveVariant(String base, boolean deep, boolean keepDeep,
                                         Map<String, Integer> nameToId) {
        if (!deep) {
            if (base.startsWith("deep_") && nameToId.containsKey(base.substring(5))) {
                return base.substring(5);
            }
            return base;
        }
        if (!keepDeep) {
            return base;
        }
        if (!base.startsWith("deep_") && nameToId.containsKey("deep_" + base)) {
            return "deep_" + base;
        }
        return base;
    }

    /**
     * 构建单季 biome 重映射表：按家族分类逐 biome 决定显示目标。
     * 陆地跟随 visual.mappings；海洋跟随 oceans.seasons（deep 变体按 keep-deep-variants 处理）；
     * 岸滩在 affect-shores 时跟随海洋；河流优先 rivers.seasons、该季未配置时在 affect-rivers 下回退跟随海洋。
     * 未命中或显示值与原值相同的条目不入表；整季无变化返回空表。
     */
    private Map<Integer, Integer> buildBiomeRemap(String season, Map<String, Integer> nameToId) {
        String landTarget = settings.visualBiomes.get(season);
        String oceanTarget = settings.oceansEnabled ? settings.oceanSeasons.get(season) : null;
        String riverTarget = settings.riversEnabled ? settings.riverSeasons.get(season) : null;
        if (riverTarget == null && settings.oceansEnabled && settings.affectRivers) {
            // rivers 段未启用或该季未配置映射时，河流回退跟随海洋映射
            riverTarget = oceanTarget;
        }
        String shoreTarget = settings.oceansEnabled && settings.affectShores ? oceanTarget : null;
        if (landTarget == null && oceanTarget == null) {
            return Map.of();
        }
        Map<Integer, Integer> remap = new HashMap<>();
        for (Map.Entry<String, Integer> entry : nameToId.entrySet()) {
            String display;
            switch (familyOf(entry.getKey())) {
                case "OCEAN" -> {
                    if (oceanTarget == null) {
                        continue;
                    }
                    display = resolveVariant(oceanTarget, entry.getKey().startsWith("deep_"),
                            settings.keepDeepVariants, nameToId);
                }
                case "SHORE" -> {
                    if (shoreTarget == null) {
                        continue;
                    }
                    display = shoreTarget;
                }
                case "RIVER" -> {
                    if (riverTarget == null) {
                        continue;
                    }
                    display = riverTarget;
                }
                default -> {
                    if (landTarget == null) {
                        continue;
                    }
                    display = landTarget;
                }
            }
            Integer displayId = nameToId.get(display);
            if (displayId == null || displayId.equals(entry.getValue())) {
                continue;
            }
            remap.put(entry.getValue(), displayId);
        }
        return Map.copyOf(remap);
    }

    /**
     * biome 容器显示重映射（仅改发包内容，真实区块不动）。
     * 单值模式直接映射；palette 模式（bits≤3）映射去重后按需退化为单值容器或以新位数重新打包
     * （valuesPerLong = 64/newBits，不跨 long）；全局模式（bits>3）逐 long 逐 entry 原位替换。
     */
    private void remapBiomeContainer(ByteReader reader, ByteArrayOutputStream output, Map<Integer, Integer> biomeMap) throws IOException {
        int bits = reader.readUnsignedByte();
        if (bits == 0) {
            // 单值 biome：VarInt biome ID + 长数组，无 palette 长度前缀
            int single = reader.readVarInt();
            int emptyLongs = reader.readVarInt();
            Integer mapped = biomeMap.get(single);
            if (mapped == null) {
                output.write(bits);
                writeVarInt(output, single);
                writeVarInt(output, emptyLongs);
                for (int i = 0; i < emptyLongs; i++) {
                    writeLong(output, reader.readLong());
                }
                return;
            }
            for (int i = 0; i < emptyLongs; i++) {
                reader.readLong();
            }
            writeSingleBiomeContainer(output, mapped);
            return;
        }
        if (bits <= 3) {
            int paletteSize = reader.readVarInt();
            int[] mapped = new int[paletteSize];
            boolean changed = false;
            Set<Integer> unique = new LinkedHashSet<>();
            for (int i = 0; i < paletteSize; i++) {
                int id = reader.readVarInt();
                Integer target = biomeMap.get(id);
                mapped[i] = target == null ? id : target;
                changed |= mapped[i] != id;
                unique.add(mapped[i]);
            }
            int longCount = reader.readVarInt();
            long[] packed = new long[longCount];
            for (int i = 0; i < longCount; i++) {
                packed[i] = reader.readLong();
            }
            if (!changed) {
                // 无变化原样透传，避免无谓的重打包
                output.write(bits);
                writeVarInt(output, paletteSize);
                for (int id : mapped) {
                    writeVarInt(output, id);
                }
                writeVarInt(output, longCount);
                for (long value : packed) {
                    writeLong(output, value);
                }
                return;
            }
            if (unique.size() == 1) {
                // 全段同一 biome：退化为单值容器并丢弃打包数据
                writeSingleBiomeContainer(output, unique.iterator().next());
                return;
            }
            int newBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(unique.size() - 1));
            output.write(newBits);
            writeVarInt(output, unique.size());
            Map<Integer, Integer> compact = new HashMap<>();
            int next = 0;
            for (int id : unique) {
                writeVarInt(output, id);
                compact.put(id, next++);
            }
            int[] indices = new int[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                indices[i] = compact.get(mapped[i]);
            }
            // 解包旧 palette 索引（biome 容器固定 64 entry，不跨 long），畸形长数据按 0 处理
            int[] entries = new int[64];
            int oldPerLong = 64 / bits;
            int index = 0;
            for (int i = 0; i < longCount && index < 64; i++) {
                for (int slot = 0; slot < oldPerLong && index < 64; slot++, index++) {
                    int paletteIndex = (int) ((packed[i] >>> (slot * bits)) & ((1L << bits) - 1L));
                    entries[index] = paletteIndex < paletteSize ? indices[paletteIndex] : 0;
                }
            }
            int perLong = 64 / newBits;
            writeVarInt(output, (64 + perLong - 1) / perLong);
            long mask = (1L << newBits) - 1L;
            for (int start = 0; start < 64; start += perLong) {
                long rebuilt = 0L;
                for (int entry = 0; entry < perLong && start + entry < 64; entry++) {
                    rebuilt |= ((long) (entries[start + entry] & (int) mask)) << (entry * newBits);
                }
                writeLong(output, rebuilt);
            }
            return;
        }
        // 全局调色板（bits > 3）：无 palette 段；数据条目按 valuesPerLong = 64/bits
        // 逐 long 独立打包（不跨 long），逐个 long 原位重映射，数组长度不变
        int longCount = reader.readVarInt();
        writeVarInt(output, longCount);
        if (biomeMap.isEmpty()) {
            for (int i = 0; i < longCount; i++) {
                writeLong(output, reader.readLong());
            }
            return;
        }
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1L;
        for (int i = 0; i < longCount; i++) {
            long packed = reader.readLong();
            long rebuilt = 0L;
            for (int entry = 0; entry < perLong; entry++) {
                int raw = (int) ((packed >>> (entry * bits)) & mask);
                int mapped = remap(raw, biomeMap);
                if (mapped >= (1 << bits)) {
                    // 目标 ID 超出位数上限，放弃该条目替换以保证发包合法
                    mapped = raw;
                }
                rebuilt |= ((long) mapped) << (entry * bits);
            }
            writeLong(output, rebuilt);
        }
    }

    private Method findNoArgMethod(Class<?> type, String name) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) {
                return method;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private Method findOneArgMethod(Class<?> type, String name, Class<?> parameter)
            throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(parameter)) {
                return method;
            }
        }
        throw new NoSuchMethodException(name + " on " + type.getName());
    }

    private Method findRegistryIdMethod(Class<?> type, Class<?> valueType)
            throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            Class<?> returnType = method.getReturnType();
            if (method.getName().equals("getId")
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(valueType)
                    && (returnType == int.class || returnType == Integer.class)) {
                return method;
            }
        }
        throw new NoSuchMethodException("getId on " + type.getName());
    }

    private byte[] rewriteSections(byte[] input, World world, Map<Integer, Integer> biomeMap, Map<Integer, Integer> blockMap) throws IOException {
        ByteReader reader = new ByteReader(input);
        ByteArrayOutputStream output = new ByteArrayOutputStream(input.length);
        int sections = (world.getMaxHeight() - world.getMinHeight()) / 16;
        for (int section = 0; section < sections; section++) {
            copyBlockContainer(reader, output, blockMap);
            if (biomeMap.isEmpty()) {
                // biome 映射缺失时透传原 biome 容器，保留方块视觉能力
                copyBiomeContainer(reader, output);
            } else {
                remapBiomeContainer(reader, output, biomeMap);
            }
        }
        if (reader.remaining() != 0) {
            return null;
        }
        return output.toByteArray();
    }

    private void copyBlockContainer(ByteReader reader, ByteArrayOutputStream output, Map<Integer, Integer> blockMap) throws IOException {
        int bits = reader.readUnsignedByte();
        output.write(bits);
        if (bits == 0) {
            // 单值容器（整段同一方块状态）：VarInt 状态 ID + 通常为空的长数组，无 palette 长度前缀
            int single = reader.readVarInt();
            writeVarInt(output, remap(single, blockMap));
            int emptyLongs = reader.readVarInt();
            writeVarInt(output, emptyLongs);
            for (int i = 0; i < emptyLongs; i++) {
                writeLong(output, reader.readLong());
            }
            return;
        }
        int paletteBits = Math.max(4, bits);
        if (bits <= 8) {
            int paletteSize = reader.readVarInt();
            writeVarInt(output, paletteSize);
            for (int i = 0; i < paletteSize; i++) {
                writeVarInt(output, remap(reader.readVarInt(), blockMap));
            }
        } else if (bits <= Short.MAX_VALUE) {
            // 全局调色板（bits > 8）：无 palette 段；数据条目按 valuesPerLong = 64/bits
            // 逐 long 独立打包（不跨 long），逐个 long 原位重映射，数组长度不变
            int longCount = reader.readVarInt();
            writeVarInt(output, longCount);
            if (blockMap.isEmpty()) {
                for (int i = 0; i < longCount; i++) {
                    writeLong(output, reader.readLong());
                }
                return;
            }
            int perLong = 64 / bits;
            long mask = (1L << bits) - 1L;
            for (int i = 0; i < longCount; i++) {
                long packed = reader.readLong();
                long rebuilt = 0L;
                for (int entry = 0; entry < perLong; entry++) {
                    int raw = (int) ((packed >>> (entry * bits)) & mask);
                    int mapped = remap(raw, blockMap);
                    if (mapped >= (1 << bits)) {
                        // 目标 ID 超出位数上限，放弃该条目替换以保证发包合法
                        mapped = raw;
                    }
                    rebuilt |= ((long) mapped) << (entry * bits);
                }
                writeLong(output, rebuilt);
            }
            return;
        }
        int longCount = reader.readVarInt();
        writeVarInt(output, longCount);
        for (int i = 0; i < longCount; i++) {
            long value = reader.readLong();
            writeLong(output, value);
        }
        if (bits > 8 && paletteBits < 9) {
            throw new IOException("非法 block palette bits");
        }
    }

    /** 方块状态 ID 显示重映射；空表或未命中时原样返回。 */
    private int remap(int id, Map<Integer, Integer> blockMap) {
        if (blockMap.isEmpty()) {
            return id;
        }
        Integer mapped = blockMap.get(id);
        return mapped == null ? id : mapped;
    }

    /** 透传复制 biome 容器（biome 映射缺失但启用了方块视觉时使用）。 */
    private void copyBiomeContainer(ByteReader reader, ByteArrayOutputStream output) throws IOException {
        int bits = reader.readUnsignedByte();
        output.write(bits);
        if (bits == 0) {
            // 单值 biome：VarInt biome ID + 长数组，无 palette 长度前缀
            writeVarInt(output, reader.readVarInt());
            int emptyLongs = reader.readVarInt();
            writeVarInt(output, emptyLongs);
            for (int i = 0; i < emptyLongs; i++) {
                writeLong(output, reader.readLong());
            }
            return;
        }
        if (bits <= 3) {
            int paletteSize = reader.readVarInt();
            writeVarInt(output, paletteSize);
            for (int i = 0; i < paletteSize; i++) {
                writeVarInt(output, reader.readVarInt());
            }
        }
        int longCount = reader.readVarInt();
        writeVarInt(output, longCount);
        for (int i = 0; i < longCount; i++) {
            writeLong(output, reader.readLong());
        }
    }

    private void writeSingleBiomeContainer(ByteArrayOutputStream output, int biomeId) throws IOException {
        output.write(0);
        writeVarInt(output, biomeId);
        writeVarInt(output, 0);
    }

    private void writeVarInt(ByteArrayOutputStream output, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            output.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    private void writeLong(ByteArrayOutputStream output, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            output.write((int) (value >>> shift) & 0xFF);
        }
    }

    void reload() {
        if (!available || !settings.visualEnabled) {
            biomeRemaps = Map.of();
            seasonBlocks = Map.of();
            return;
        }
        // 局部构建完成后整体替换字段：发包线程只读快照，reload 与 rewrite 无需加锁
        Map<String, Map<Integer, Integer>> remaps = new HashMap<>();
        try {
            Map<String, Integer> registryNames = loadRegistryBiomes();
            Set<String> seasons = new HashSet<>();
            seasons.addAll(settings.visualBiomes.keySet());
            seasons.addAll(settings.oceanSeasons.keySet());
            seasons.addAll(settings.riverSeasons.keySet());
            for (String season : seasons) {
                Map<Integer, Integer> remap = buildBiomeRemap(season, registryNames);
                if (!remap.isEmpty()) {
                    remaps.put(season, remap);
                }
            }
        } catch (IOException exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "无法构建季节 biome 视觉映射，biome 视觉已降级为透传", exception);
        }
        biomeRemaps = Map.copyOf(remaps);
        if (!biomeRemaps.isEmpty()) {
            plugin.getLogger().info("季节 biome 视觉映射已启用：" + biomeRemaps.keySet());
        }
        buildSeasonBlocks();
    }

    /**
     * 构建每季方块状态 ID 映射（秋叶/植被显示替换）。
     * 反射失败返回空映射，对应季节方块视觉自动跳过，不影响 biome 视觉。
     */
    private void buildSeasonBlocks() {
        Set<String> seasons = new HashSet<>();
        if (settings.floraEnabled) {
            seasons.addAll(settings.floraSeasons.keySet());
        }
        if (settings.foliageEnabled) {
            seasons.addAll(settings.foliageSeasons);
        }
        Map<String, Map<Integer, Integer>> blocks = new HashMap<>();
        for (String season : seasons) {
            Map<String, String> materials = new HashMap<>();
            if (settings.floraEnabled) {
                materials.putAll(settings.floraSeasons.getOrDefault(season, Map.of()));
            }
            if (settings.foliageEnabled && settings.foliageSeasons.contains(season)) {
                // 同名源材料以秋叶映射为准
                materials.putAll(settings.foliageMap);
            }
            if (materials.isEmpty()) {
                continue;
            }
            Map<Integer, Integer> ids = SeasonBlockPaletteMapper.build(plugin, materials);
            if (!ids.isEmpty()) {
                blocks.put(season, ids);
            }
        }
        seasonBlocks = Map.copyOf(blocks);
        if (!seasonBlocks.isEmpty()) {
            plugin.getLogger().info("季节方块视觉（秋叶/植被显示替换）已启用：" + seasonBlocks.keySet());
        }
    }

    void refresh() {
        if (!available || !settings.resend) {
            return;
        }
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        refreshQueue.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();
            if (settings.disabledWorlds.contains(world.getName())) {
                continue;
            }
            int view = Math.max(1, player.getClientViewDistance());
            int centerX = player.getLocation().getBlockX() >> 4;
            int centerZ = player.getLocation().getBlockZ() >> 4;
            for (int x = centerX - view; x <= centerX + view; x++) {
                for (int z = centerZ - view; z <= centerZ + view; z++) {
                    refreshQueue.add(new RefreshTarget(world, x, z));
                }
            }
        }
        // ease>0：按显示比例步进平滑过渡；ease=0：保持每 tick 一批全量刷完
        double ease = settings.ease;
        int batch = ease > 0.0
                ? Math.max(1, (int) Math.ceil(refreshQueue.size() * ease))
                : settings.refreshBatchSize;
        long period = ease > 0.0 ? Math.max(1, settings.easeTicks) : 1L;
        refreshTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int count = 0;
            long started = System.nanoTime();
            while (count++ < batch && !refreshQueue.isEmpty()) {
                RefreshTarget target = refreshQueue.removeFirst();
                target.world().refreshChunk(target.x(), target.z());
                refreshedChunks++;
            }
            refreshNanos += System.nanoTime() - started;
            if (refreshQueue.isEmpty()) {
                refreshTask.cancel();
                refreshTask = null;
                plugin.getLogger().info("季节视觉刷新完成：区块 " + refreshedChunks + "，耗时 "
                        + (refreshNanos / 1_000_000) + "ms；发包成功 " + rewritten
                        + "，跳过 " + skipped + "，失败 " + failed);
                refreshedChunks = 0;
                refreshNanos = 0;
                rewritten = 0;
                skipped = 0;
                failed = 0;
            }
        }, 1L, period);
    }

    void close() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        refreshQueue.clear();
        if (listener != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(listener);
            listener = null;
        }
        available = false;
        biomeRemaps = Map.of();
        seasonBlocks = Map.of();
    }

    private record RefreshTarget(World world, int x, int z) {
    }

    private static final class ByteReader {
        private final byte[] data;
        private int index;

        private ByteReader(byte[] data) {
            this.data = data;
        }

        private int remaining() {
            return data.length - index;
        }

        private int readUnsignedByte() throws IOException {
            if (index >= data.length) {
                throw new IOException("区块数据提前结束");
            }
            return data[index++] & 0xFF;
        }

        private int readVarInt() throws IOException {
            int result = 0;
            int shift = 0;
            while (true) {
                int value = readUnsignedByte();
                result |= (value & 0x7F) << shift;
                if ((value & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift > 28) {
                    throw new IOException("区块数据 VarInt 过长");
                }
            }
        }

        private long readLong() throws IOException {
            long result = 0;
            for (int shift = 56; shift >= 0; shift -= 8) {
                result |= (long) readUnsignedByte() << shift;
            }
            return result;
        }
    }
}
