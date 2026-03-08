package com.msgames.plugin.stackmultipliercontainer.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.data.UniqueItemUsagesComponent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.StackMultiplierContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * Parent command for backpack testing. Subcommands:
 * <ul>
 *     <li>{@code /smcbackpack info} — shows current backpack capacity and stack multiplier</li>
 *     <li>{@code /smcbackpack set <capacity> <multiplier>} — sets absolute values</li>
 *     <li>{@code /smcbackpack upgrade <capacityIncrease> <multiplierFactor>} — applies relative upgrade</li>
 *     <li>{@code /smcbackpack reset} — resets backpack to vanilla defaults</li>
 * </ul>
 */
public class BackpackCommand extends AbstractCommand {

    private static final Field BACKPACK_FIELD;
    private static final Method BUILD_COMBINED_METHOD;
    private static final Method MARK_CHANGED_METHOD;
    private static final Method REGISTER_BACKPACK_LISTENER_METHOD;

    static {
        try {
            BACKPACK_FIELD = Inventory.class.getDeclaredField("backpack");
            BACKPACK_FIELD.setAccessible(true);
            BUILD_COMBINED_METHOD = Inventory.class.getDeclaredMethod("buildCombinedContains");
            BUILD_COMBINED_METHOD.setAccessible(true);
            MARK_CHANGED_METHOD = Inventory.class.getDeclaredMethod("markChanged");
            MARK_CHANGED_METHOD.setAccessible(true);
            REGISTER_BACKPACK_LISTENER_METHOD = Inventory.class.getDeclaredMethod("registerBackpackListener");
            REGISTER_BACKPACK_LISTENER_METHOD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public BackpackCommand() {
        super("smcbackpack", "Stack Multiplier Container backpack test commands");
        addSubCommand(new InfoCommand());
        addSubCommand(new SetCommand());
        addSubCommand(new UpgradeCommand());
        addSubCommand(new ResetCommand());
        addSubCommand(new ClearUsagesCommand());
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext context) {
        context.sendMessage(Message.raw("Usage: /smcbackpack <info|set|upgrade|reset|clearusages>"));
        return CompletableFuture.completedFuture(null);
    }

    private static void replaceBackpack(Inventory inventory, ItemContainer newBackpack) {
        try {
            ItemContainer.copy(inventory.getBackpack(), newBackpack, null);
            BACKPACK_FIELD.set(inventory, newBackpack);
            BUILD_COMBINED_METHOD.invoke(inventory);
            REGISTER_BACKPACK_LISTENER_METHOD.invoke(inventory);
            MARK_CHANGED_METHOD.invoke(inventory);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to modify backpack", e);
        }
    }

    // /smcbackpack info
    private static class InfoCommand extends AbstractPlayerCommand {
        InfoCommand() {
            super("info", "Shows current backpack capacity and stack multiplier");
        }

        @Override
        protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef, World world) {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) return;

            Inventory inventory = player.getInventory();
            ItemContainer backpack = inventory.getBackpack();
            short capacity = backpack.getCapacity();
            int multiplier = 1;
            String type = backpack.getClass().getSimpleName();

            if (backpack instanceof StackMultiplierContainer smc) {
                multiplier = smc.getStackMultiplier();
            }

            context.sendMessage(Message.raw("Backpack Info:"));
            context.sendMessage(Message.raw("  Type: " + type));
            context.sendMessage(Message.raw("  Capacity: " + capacity + " slots"));
            context.sendMessage(Message.raw("  Stack Multiplier: " + multiplier + "x"));
        }
    }

    // /smcbackpack set <capacity> <multiplier>
    private static class SetCommand extends AbstractPlayerCommand {
        private final RequiredArg<Integer> capacityArg;
        private final RequiredArg<Integer> multiplierArg;

        SetCommand() {
            super("set", "Sets backpack to exact capacity and stack multiplier");
            capacityArg = withRequiredArg("capacity", "Number of slots", ArgTypes.INTEGER);
            multiplierArg = withRequiredArg("multiplier", "Stack size multiplier", ArgTypes.INTEGER);
        }

        @Override
        protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef, World world) {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) return;

            int capacity = context.get(capacityArg);
            int multiplier = context.get(multiplierArg);

            if (capacity < 1 || capacity > Short.MAX_VALUE) {
                context.sendMessage(Message.raw("Capacity must be between 1 and " + Short.MAX_VALUE));
                return;
            }
            if (multiplier < 1) {
                context.sendMessage(Message.raw("Multiplier must be at least 1"));
                return;
            }

            Inventory inventory = player.getInventory();
            StackMultiplierContainer newBackpack = new StackMultiplierContainer((short) capacity, (short) multiplier);
            replaceBackpack(inventory, newBackpack);

            context.sendMessage(Message.raw("Backpack set to " + capacity + " slots with " + multiplier + "x stack multiplier."));
        }
    }

    // /smcbackpack upgrade <capacityIncrease> <multiplierFactor>
    private static class UpgradeCommand extends AbstractPlayerCommand {
        private final RequiredArg<Integer> capacityIncreaseArg;
        private final RequiredArg<Integer> multiplierFactorArg;

        UpgradeCommand() {
            super("upgrade", "Adds capacity and multiplies stack multiplier");
            capacityIncreaseArg = withRequiredArg("capacityIncrease", "Slots to add", ArgTypes.INTEGER);
            multiplierFactorArg = withRequiredArg("multiplierFactor", "Multiply current multiplier by this", ArgTypes.INTEGER);
        }

        @Override
        protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef, World world) {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) return;

            int capacityIncrease = context.get(capacityIncreaseArg);
            int multiplierFactor = context.get(multiplierFactorArg);

            if (capacityIncrease < 0) {
                context.sendMessage(Message.raw("Capacity increase must be >= 0"));
                return;
            }
            if (multiplierFactor < 1) {
                context.sendMessage(Message.raw("Multiplier factor must be at least 1"));
                return;
            }

            Inventory inventory = player.getInventory();
            ItemContainer currentBackpack = inventory.getBackpack();
            short currentCapacity = currentBackpack.getCapacity();
            int currentMultiplier = 1;
            if (currentBackpack instanceof StackMultiplierContainer smc) {
                currentMultiplier = smc.getStackMultiplier();
            }

            short newCapacity = (short) (currentCapacity + capacityIncrease);
            int newMultiplier = currentMultiplier * multiplierFactor;

            StackMultiplierContainer newBackpack = new StackMultiplierContainer(newCapacity, (short) newMultiplier);
            replaceBackpack(inventory, newBackpack);

            context.sendMessage(Message.raw("Backpack upgraded: " + currentCapacity + " -> " + newCapacity + " slots, "
                    + currentMultiplier + "x -> " + newMultiplier + "x stack multiplier."));
        }
    }

    // /smcbackpack reset
    private static class ResetCommand extends AbstractPlayerCommand {
        ResetCommand() {
            super("reset", "Resets backpack to vanilla defaults (0 capacity, no multiplier)");
        }

        @Override
        protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef, World world) {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) return;

            Inventory inventory = player.getInventory();
            inventory.resizeBackpack((short) 0, null);

            context.sendMessage(Message.raw("Backpack reset to vanilla defaults."));
        }
    }

    // /smcbackpack clearusages
    private static class ClearUsagesCommand extends AbstractPlayerCommand {
        private static final Field USED_UNIQUE_ITEMS_FIELD;

        static {
            try {
                USED_UNIQUE_ITEMS_FIELD = UniqueItemUsagesComponent.class.getDeclaredField("usedUniqueItems");
                USED_UNIQUE_ITEMS_FIELD.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        ClearUsagesCommand() {
            super("clearusages", "Clears all unique item usage records, allowing re-use of upgrade items");
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef, World world) {
            UniqueItemUsagesComponent usages = store.getComponent(ref, UniqueItemUsagesComponent.getComponentType());
            if (usages == null) {
                context.sendMessage(Message.raw("No unique item usage data found."));
                return;
            }

            try {
                java.util.Set<String> usedItems = (java.util.Set<String>) USED_UNIQUE_ITEMS_FIELD.get(usages);
                int count = usedItems.size();
                usedItems.clear();
                context.sendMessage(Message.raw("Cleared " + count + " unique item usage records."));
            } catch (ReflectiveOperationException e) {
                context.sendMessage(Message.raw("Failed to clear usages: " + e.getMessage()));
            }
        }
    }
}
