package org.example2.solips;

public final class ClientFeatureToggle {
    private static boolean enabled = true;

    private ClientFeatureToggle() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean toggle() {
        enabled = !enabled;
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }
}
