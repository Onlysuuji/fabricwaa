package org.example2.solips;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class ManualResetKeyHandler {
    private static KeyBinding resetAllMapping;
    private static KeyBinding toggleEnabledMapping;
    private static boolean initialized = false;

    private ManualResetKeyHandler() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        resetAllMapping = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.solips.reset_all",
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
        while (resetAllMapping.wasPressed()) {
            triggerReset(client);
        }
    }

    private static void triggerReset(MinecraftClient client) {
        SeedCrackState.resetAll();
        PlayerSeedPredictState.resetAll();
        EnchantScreenObserver.clearClientObservationState();
        System.out.println("[manual-reset] cleared by M key");

        if (client.player != null) {
            client.player.sendMessage(Text.literal("[solips] reset all"), true);
        }
    }

    private static void triggerToggle(MinecraftClient client) {
        boolean enabled = ClientFeatureToggle.toggle();
        if (!enabled) {
            SeedCrackState.resetAll();
            PlayerSeedPredictState.resetAll();
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
