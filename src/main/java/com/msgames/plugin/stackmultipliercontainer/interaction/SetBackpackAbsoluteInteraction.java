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
 * An interaction that sets the player's backpack to exact capacity and stack multiplier values.
 * Unlike {@link SetBackpackStackMultiplierInteraction}, which applies relative changes,
 * this interaction sets absolute values.
 */
public class SetBackpackAbsoluteInteraction extends SimpleInstantInteraction {

    public static final BuilderCodec<SetBackpackAbsoluteInteraction> CODEC;

    private short capacity;
    private int stackMultiplier;

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
                        SetBackpackAbsoluteInteraction.class,
                        SetBackpackAbsoluteInteraction::new,
                        SimpleInstantInteraction.CODEC
                )
                .documentation("Sets the player's backpack to exact capacity and stack multiplier values.")
                .append(
                        new KeyedCodec<>("Capacity", Codec.SHORT),
                        (SetBackpackAbsoluteInteraction o, Short i) -> o.capacity = i,
                        (SetBackpackAbsoluteInteraction o) -> o.capacity
                )
                .addValidator(Validators.greaterThan((short) 0))
                .add()
                .append(
                        new KeyedCodec<>("StackMultiplier", Codec.INTEGER),
                        (SetBackpackAbsoluteInteraction o, Integer i) -> o.stackMultiplier = i,
                        (SetBackpackAbsoluteInteraction o) -> o.stackMultiplier
                )
                .addValidator(Validators.greaterThanOrEqual(1))
                .add()
                .build();
    }

    public SetBackpackAbsoluteInteraction() {
        this.capacity = 9;
        this.stackMultiplier = 1;
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

        try {
            StackMultiplierContainer newBackpack = new StackMultiplierContainer(this.capacity, (short) this.stackMultiplier);
            ItemContainer.copy(currentBackpack, newBackpack, null);

            BACKPACK_FIELD.set(inventory, newBackpack);
            BUILD_COMBINED_METHOD.invoke(inventory);
            REGISTER_BACKPACK_LISTENER_METHOD.invoke(inventory);
            MARK_CHANGED_METHOD.invoke(inventory);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to set backpack", e);
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
        return "SetBackpackAbsoluteInteraction{capacity=" + capacity
                + ", stackMultiplier=" + stackMultiplier + "} " + super.toString();
    }
}
