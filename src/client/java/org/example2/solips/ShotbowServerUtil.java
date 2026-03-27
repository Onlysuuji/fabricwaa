package org.example2.solips;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.util.Locale;

public final class ShotbowServerUtil {
    private static final String[] SHOTBOW_HOSTS = {
            "us.shotbow.net",
            "jp.shotbow.net",
            "play.shotbow.net"
    };

    private ShotbowServerUtil() {
    }

    public static String resolveShotbowHost(MinecraftClient client) {
        if (client == null) {
            return null;
        }

        ServerInfo serverInfo = client.getCurrentServerEntry();
        if (serverInfo == null || serverInfo.address == null || serverInfo.address.isBlank()) {
            return null;
        }

        String host = normalizeServerHost(serverInfo.address);
        for (String shotbowHost : SHOTBOW_HOSTS) {
            if (shotbowHost.equals(host)) {
                return shotbowHost;
            }
        }
        return null;
    }

    public static boolean isOnShotbow(MinecraftClient client) {
        return resolveShotbowHost(client) != null;
    }

    private static String normalizeServerHost(String address) {
        String normalized = address.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        int colonIndex = normalized.lastIndexOf(':');
        if (colonIndex > 0 && normalized.indexOf(':') == colonIndex) {
            normalized = normalized.substring(0, colonIndex);
        }

        return normalized;
    }
}
