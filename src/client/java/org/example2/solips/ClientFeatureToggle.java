package org.example2.solips;

public final class ClientFeatureToggle {
    private static boolean enabled = true;
    private static boolean hudVisible = true;
    private static boolean autoInsertEnabled = true;

    private ClientFeatureToggle() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean toggle() {
        enabled = !enabled;
        return enabled;
    }

    public static boolean isHudVisible() {
        return hudVisible;
    }

    public static boolean toggleHudVisible() {
        hudVisible = !hudVisible;
        return hudVisible;
    }

    public static boolean isAutoInsertEnabled() {
        return autoInsertEnabled;
    }

    public static boolean toggleAutoInsert() {
        autoInsertEnabled = !autoInsertEnabled;
        return autoInsertEnabled;
    }

}
