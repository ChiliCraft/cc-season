package com.chilicraft.season;

import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 把「材料 → 材料」的发包视觉映射展开为「方块状态数字 ID → 方块状态数字 ID」的全量映射。
 *
 * <p>为什么反射：Bukkit 不暴露全局方块状态注册表的数字 ID，而区块发包重写只认识 ID；
 * 这里复用 {@link ProtocolVisualAdapter} 的 biome ID 反射路径，所有反射仅在初始化/重载时执行一次。
 * 任何反射失败都返回空映射，调用方据此跳过方块视觉替换，不影响 biome 视觉与季节功能。</p>
 */
final class SeasonBlockPaletteMapper {

    private SeasonBlockPaletteMapper() {
    }

    /**
     * @param materialMap 源材料名（小写）→ 目标材料名（小写）
     * @return 方块状态 ID 映射；属性组合无对应目标状态时该组合直接缺席（保持原样）
     */
    static Map<Integer, Integer> build(ChiliSeasonPlugin plugin, Map<String, String> materialMap) {
        if (materialMap.isEmpty()) {
            return Map.of();
        }
        try {
            Object registryAccess = registryAccess();
            Object resourceKey = blockResourceKey();
            Object registry = registryOrThrow(registryAccess, resourceKey);
            Method getId = findRegistryIdMethod(registry);

            Map<Integer, Integer> out = new HashMap<>();
            for (Map.Entry<String, String> entry : materialMap.entrySet()) {
                Object sourceBlock = block(registry, resourceKey, entry.getKey());
                Object targetBlock = block(registry, resourceKey, entry.getValue());
                if (sourceBlock == null || targetBlock == null) {
                    plugin.getLogger().warning("方块视觉映射存在未知方块：" + entry.getKey() + " -> " + entry.getValue());
                    continue;
                }
                Map<String, Object> targetStates = statesByKey(targetBlock);
                for (Object sourceState : possibleStates(sourceBlock)) {
                    Object targetState = targetStates.get(valuesKey(sourceState));
                    if (targetState == null) {
                        continue;
                    }
                    int from = ((Number) getId.invoke(registry, sourceState)).intValue();
                    int to = ((Number) getId.invoke(registry, targetState)).intValue();
                    if (from != to) {
                        out.put(from, to);
                    }
                }
            }
            return Map.copyOf(out);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().warning("方块视觉映射构建失败，已跳过（" + exception.getMessage() + "）。");
            return Map.of();
        }
    }

    private static Object registryAccess() throws ReflectiveOperationException {
        Class<?> craftWorld = Class.forName("org.bukkit.craftbukkit.CraftWorld");
        Method getHandle = craftWorld.getMethod("getHandle");
        Object handle = getHandle.invoke(Bukkit.getWorlds().get(0));
        Method registryAccessMethod = findNoArgMethod(handle.getClass(), "registryAccess");
        return registryAccessMethod.invoke(handle);
    }

    private static Object blockResourceKey() throws ReflectiveOperationException {
        Class<?> registries = Class.forName("net.minecraft.core.registries.Registries");
        return registries.getField("BLOCK").get(null);
    }

    private static Object registryOrThrow(Object registryAccess, Object resourceKey) throws ReflectiveOperationException {
        Method registryOrThrow = findOneArgMethod(registryAccess.getClass(), "registryOrThrow", resourceKey.getClass());
        return registryOrThrow.invoke(registryAccess, resourceKey);
    }

    private static Object block(Object registry, Object resourceKey, String materialName) throws ReflectiveOperationException {
        Class<?> resourceLocation = Class.forName("net.minecraft.resources.ResourceLocation");
        Method tryParse = resourceLocation.getMethod("tryParse", String.class);
        Object location = tryParse.invoke(null, "minecraft:" + materialName.toLowerCase(Locale.ROOT));
        if (location == null) {
            return null;
        }
        Class<?> resourceKeyClass = Class.forName("net.minecraft.resources.ResourceKey");
        Method create = resourceKeyClass.getMethod("create", resourceKey.getClass(), resourceLocation);
        Object key = create.invoke(null, resourceKey, location);
        Method get = findOneArgMethod(registry.getClass(), "get", resourceKeyClass);
        return get.invoke(registry, key);
    }

    private static Collection<?> possibleStates(Object block) throws ReflectiveOperationException {
        Method getStateDefinition = findNoArgMethod(block.getClass(), "getStateDefinition");
        Object definition = getStateDefinition.invoke(block);
        Method getPossibleStates = findNoArgMethod(definition.getClass(), "getPossibleStates");
        return (Collection<?>) getPossibleStates.invoke(definition);
    }

    private static Map<String, Object> statesByKey(Object block) throws ReflectiveOperationException {
        Map<String, Object> out = new HashMap<>();
        for (Object state : possibleStates(block)) {
            out.put(valuesKey(state), state);
        }
        return out;
    }

    /** 属性 → 值的规范字符串；无属性方块为空串。 */
    private static String valuesKey(Object state) throws ReflectiveOperationException {
        Method getValues = findNoArgMethod(state.getClass(), "getValues");
        Map<?, ?> values = (Map<?, ?>) getValues.invoke(state);
        if (values.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            Method getName = findNoArgMethod(entry.getKey().getClass(), "getName");
            builder.append(getName.invoke(entry.getKey())).append('=').append(entry.getValue()).append(';');
        }
        return builder.toString();
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) {
                return method;
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new IllegalStateException("找不到方法 " + type.getName() + "#" + name);
    }

    private static Method findOneArgMethod(Class<?> type, String name, Class<?> parameter) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(parameter)) {
                return method;
            }
        }
        throw new IllegalStateException("找不到方法 " + type.getName() + "#" + name + "(" + parameter.getName() + ")");
    }

    private static Method findRegistryIdMethod(Object registry) {
        for (Method method : registry.getClass().getMethods()) {
            if (method.getName().equals("getId") && method.getParameterCount() == 1) {
                return method;
            }
        }
        throw new IllegalStateException("找不到注册表 getId 方法：" + registry.getClass().getName());
    }
}
