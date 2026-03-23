package org.example2.solips;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.EnchantmentScreen;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.screen.EnchantmentScreenHandler;

public final class EnchantHintHudRenderer {
    private static boolean initialized = false;

    private EnchantHintHudRenderer() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        HudRenderCallback.EVENT.register(EnchantHintHudRenderer::onHudRender);
    }

    private static void onHudRender(DrawContext drawContext, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(client.currentScreen instanceof EnchantmentScreen)) {
            return;
        }
        if (client.player == null || !(client.player.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
            return;
        }

        int guiLeft = Math.max(4, (client.getWindow().getScaledWidth() - 176) / 2);
        int guiTop = Math.max(4, (client.getWindow().getScaledHeight() - 166) / 2);
        EnchantHintOverlay.render(handler, drawContext, guiLeft, guiTop);
    }
}
