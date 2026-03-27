package org.example2.solips;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class ManualResetKeyHandler {
    private static KeyBinding toggleHudMapping;
    private static KeyBinding toggleEnabledMapping;
    private static boolean initialized = false;

    private ManualResetKeyHandler() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        toggleHudMapping = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.solips.toggle_hud",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "key.categories.solips"
        ));

        toggleEnabledMapping = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.solips.toggle_enabled",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                "key.categories.solips"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(ManualResetKeyHandler::onClientTick);
    }

    private static void onClientTick(MinecraftClient client) {
        while (toggleEnabledMapping.wasPressed()) {
            triggerToggle(client);
        }
        while (toggleHudMapping.wasPressed()) {
            triggerHudToggle(client);
        }
    }

    private static void triggerHudToggle(MinecraftClient client) {
        boolean visible = ClientFeatureToggle.toggleHudVisible();
        System.out.println(visible ? "[hud] shown by M key" : "[hud] hidden by M key");

        if (client.player != null) {
            client.player.sendMessage(Text.literal(visible ? "[solips] hud shown" : "[solips] hud hidden"), true);
        }
    }

    private static void triggerToggle(MinecraftClient client) {
        boolean enabled = ClientFeatureToggle.toggle();
        if (!enabled) {
            SeedCrackState.resetAll();
            EnchantScreenObserver.clearClientObservationState();
            System.out.println("[toggle] disabled by N key");
        } else {
            System.out.println("[toggle] enabled by N key");
        }

        if (client.player != null) {
            client.player.sendMessage(Text.literal(enabled ? "[solips] enabled" : "[solips] disabled"), true);
        }
    }
}
