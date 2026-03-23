package org.example2.solips;

import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.EnchantmentLevelEntry;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.server.network.ServerPlayerEntity;
import org.example2.solips.mixin.client.EnchantmentScreenHandlerAccessor;

import java.util.List;

public final class EnchantmentScreenHandlerUtil {
    private EnchantmentScreenHandlerUtil() {
    }

    public static DynamicRegistryManager getRegistryManager(MinecraftClient client) {
        if (client.world != null) {
            return client.world.getRegistryManager();
        }
        if (client.isIntegratedServerRunning() && client.getServer() != null) {
            ServerPlayerEntity serverPlayer = client.player == null ? null : client.getServer().getPlayerManager().getPlayer(client.player.getUuid());
            if (serverPlayer != null) {
                return serverPlayer.getWorld().getRegistryManager();
            }
        }
        return null;
    }

    public static boolean setMenuSeed(EnchantmentScreenHandler menu, int seed) {
        try {
            ((EnchantmentScreenHandlerAccessor) menu).solips$getSeedProperty().set(seed);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static List<EnchantmentLevelEntry> generateEnchantments(
            EnchantmentScreenHandler menu,
            DynamicRegistryManager registryManager,
            ItemStack stack,
            int slot,
            int cost
    ) {
        try {
            return ((EnchantmentScreenHandlerAccessor) menu)
                    .solips$invokeGenerateEnchantments(registryManager, stack, slot, cost);
        } catch (Throwable t) {
            return null;
        }
    }

    public static EnchantmentLevelEntry pickDisplayedClue(EnchantmentScreenHandler menu, List<EnchantmentLevelEntry> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return list.get(((EnchantmentScreenHandlerAccessor) menu).solips$getRandom().nextInt(list.size()));
        } catch (Throwable t) {
            return list.get(0);
        }
    }

    public static ScreenHandlerContext getContext(EnchantmentScreenHandler menu) {
        try {
            return ((EnchantmentScreenHandlerAccessor) menu).solips$getContext();
        } catch (Throwable t) {
            return null;
        }
    }
}
