package com.msgames.plugin.stackmultipliercontainer;

import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.StackMultiplierContainer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import java.util.logging.Level;

public class StackMultiplierContainerPlugin extends JavaPlugin {

    public StackMultiplierContainerPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        getCodecRegistry(ItemContainer.CODEC).register(
                "stack_multiplier_container",
                StackMultiplierContainer.class,
                StackMultiplierContainer.CODEC
        );
        getLogger().at(Level.INFO).log("StackMultiplierContainerPlugin set up!");
    }
}
