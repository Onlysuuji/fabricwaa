package org.example2.solips;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SolipsClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger(Solips.MOD_ID);

    @Override
    public void onInitializeClient() {
        ManualResetKeyHandler.initialize();
        EnchantScreenObserver.initialize();
        MultiItemPreviewOverlay.initialize();
        EnchantHintHudRenderer.initialize();
        LOGGER.info("Initialized Fabric port of {}", Solips.MOD_ID);
    }
}
