package ru.studhub.hungergames.compat;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/** Optional Curios adapter. No Curios classes are linked when the API is absent. */
public final class CuriosInventory {
    private static Method inventory;
    private static Method curios;
    private static Method stacks;
    private static Method cosmetics;

    private CuriosInventory() {}

    public static void visit(LivingEntity player, Consumer<IItemHandlerModifiable> consumer) {
        visitNamed(player, (name, handler) -> consumer.accept(handler));
    }

    public static void visitNamed(LivingEntity player, BiConsumer<String, IItemHandlerModifiable> consumer) {
        if (!ModList.get().isLoaded("curios")) return;
        try {
            if (inventory == null) {
                inventory = Class.forName("top.theillusivec4.curios.api.CuriosApi").getMethod("getCuriosInventory", LivingEntity.class);
                curios = Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler").getMethod("getCurios");
                Class<?> handler = Class.forName("top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler");
                stacks = handler.getMethod("getStacks");
                cosmetics = handler.getMethod("getCosmeticStacks");
            }
            Optional<?> result = (Optional<?>) inventory.invoke(null, player);
            if (result.isEmpty()) return;
            for (var entry : ((Map<?, ?>) curios.invoke(result.get())).entrySet()) {
                Object handler = entry.getValue();
                consumer.accept(entry.getKey() + ":equipped", (IItemHandlerModifiable) stacks.invoke(handler));
                consumer.accept(entry.getKey() + ":cosmetic", (IItemHandlerModifiable) cosmetics.invoke(handler));
            }
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalStateException("Не удалось проверить аксессуары Curios; действие отменено для сохранности вещей.", e);
        }
    }
}
