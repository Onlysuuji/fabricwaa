package org.example2.solips.mixin.client;

import net.minecraft.enchantment.EnchantmentLevelEntry;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.Property;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(EnchantmentScreenHandler.class)
public interface EnchantmentScreenHandlerAccessor {
    @Accessor("seed")
    Property solips$getSeedProperty();

    @Accessor("random")
    Random solips$getRandom();

    @Accessor("context")
    ScreenHandlerContext solips$getContext();

    @Invoker("generateEnchantments")
    List<EnchantmentLevelEntry> solips$invokeGenerateEnchantments(
            DynamicRegistryManager registryManager,
            ItemStack stack,
            int slot,
            int level
    );
}
