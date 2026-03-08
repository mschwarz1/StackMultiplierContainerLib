package com.msgames.plugin.stackmultipliercontainer.interaction;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.validation.Validators;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.StackMultiplierContainer;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * An interaction that upgrades the player's backpack. Supports two optional operations:
 * <ul>
 *     <li>{@code CapacityIncrease} — adds slots to the current backpack (additive)</li>
 *     <li>{@code StackMultiplierFactor} — multiplies the current stack multiplier (multiplicative)</li>
 * </ul>
 * If the backpack is not already a {@link StackMultiplierContainer}, it is converted to one
 * (with a default multiplier of 1) before applying changes.
 */
public class SetBackpackStackMultiplierInteraction extends SimpleInstantInteraction {

    public static final BuilderCodec<SetBackpackStackMultiplierInteraction> CODEC;

    private short capacityIncrease;
    private int stackMultiplierFactor;

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

        CODEC = BuilderCodec.builder(
                        SetBackpackStackMultiplierInteraction.class,
                        SetBackpackStackMultiplierInteraction::new,
                        SimpleInstantInteraction.CODEC
                )
                .documentation("Upgrades the player's backpack. Adds capacity and/or multiplies the stack multiplier.")
                .append(
                        new KeyedCodec<>("CapacityIncrease", Codec.SHORT),
                        (SetBackpackStackMultiplierInteraction o, Short i) -> o.capacityIncrease = i,
                        (SetBackpackStackMultiplierInteraction o) -> o.capacityIncrease
                )
                .addValidator(Validators.greaterThanOrEqual((short) 0))
                .add()
                .append(
                        new KeyedCodec<>("StackMultiplierFactor", Codec.INTEGER),
                        (SetBackpackStackMultiplierInteraction o, Integer i) -> o.stackMultiplierFactor = i,
                        (SetBackpackStackMultiplierInteraction o) -> o.stackMultiplierFactor
                )
                .addValidator(Validators.greaterThanOrEqual(1))
                .add()
                .build();
    }

    public SetBackpackStackMultiplierInteraction() {
        this.capacityIncrease = 0;
        this.stackMultiplierFactor = 1;
    }

    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        Ref<?> entityRef = context.getEntity();
        CommandBuffer commandBuffer = context.getCommandBuffer();
        Player player = (Player) commandBuffer.getComponent(entityRef, Player.getComponentType());

        if (player == null) {
            return;
        }

        Inventory inventory = player.getInventory();
        ItemContainer currentBackpack = inventory.getBackpack();
        short currentCapacity = currentBackpack.getCapacity();

        if (currentCapacity <= 0 && capacityIncrease <= 0) {
            return;
        }

        // Determine the current and new values
        int currentMultiplier = 1;
        if (currentBackpack instanceof StackMultiplierContainer smc) {
            currentMultiplier = smc.getStackMultiplier();
        }

        short newCapacity = (short) (currentCapacity + capacityIncrease);
        int newMultiplier = currentMultiplier * stackMultiplierFactor;

        try {
            // Create new container with updated capacity and multiplier, copy items over
            StackMultiplierContainer newBackpack = new StackMultiplierContainer(newCapacity, (short) newMultiplier);
            ItemContainer.copy(currentBackpack, newBackpack, null);

            BACKPACK_FIELD.set(inventory, newBackpack);
            BUILD_COMBINED_METHOD.invoke(inventory);
            REGISTER_BACKPACK_LISTENER_METHOD.invoke(inventory);
            MARK_CHANGED_METHOD.invoke(inventory);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to upgrade backpack", e);
        }

        // Consume the upgrade item
        context.getHeldItemContainer().removeItemStackFromSlot(
                (short) context.getHeldItemSlot(),
                context.getHeldItem(),
                1
        );
    }

    @Override
    public String toString() {
        return "SetBackpackStackMultiplierInteraction{capacityIncrease=" + capacityIncrease
                + ", stackMultiplierFactor=" + stackMultiplierFactor + "} " + super.toString();
    }
}
