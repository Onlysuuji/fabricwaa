package org.example2.solips;

public final class PlayerSeedPredictState {
    private static final int NONE_SEED = Integer.MIN_VALUE;
    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK = (1L << 48) - 1L;

    private enum Status {
        WAITING_FIRST,
        WAITING_SECOND,
        PREDICTING
    }

    private static volatile Status status = Status.WAITING_FIRST;
    private static volatile int anchorXpSeed = NONE_SEED;
    private static volatile int lastObservedXpSeed = NONE_SEED;
    private static volatile int predictedNextXpSeed = NONE_SEED;
    private static volatile long currentInternalSeed = 0L;
    private static volatile long predictedInternalSeed = 0L;
    private static volatile String invalidationReason = "";

    private PlayerSeedPredictState() {
    }

    public static synchronized void resetAll() {
        status = Status.WAITING_FIRST;
        anchorXpSeed = NONE_SEED;
        lastObservedXpSeed = NONE_SEED;
        predictedNextXpSeed = NONE_SEED;
        currentInternalSeed = 0L;
        predictedInternalSeed = 0L;
        invalidationReason = "";
    }

    public static synchronized void observeXpSeed(int xpSeed) {
        if (xpSeed == NONE_SEED) {
            return;
        }
        if (lastObservedXpSeed == xpSeed) {
            return;
        }
        lastObservedXpSeed = xpSeed;

        if (status == Status.PREDICTING && predictedNextXpSeed != NONE_SEED) {
            if (xpSeed == predictedNextXpSeed) {
                currentInternalSeed = predictedInternalSeed;
                predictedInternalSeed = nextState(currentInternalSeed);
                predictedNextXpSeed = outputFromState(predictedInternalSeed);
                anchorXpSeed = xpSeed;
                invalidationReason = "";
                return;
            }

            invalidatePredictionKeepLastObserved("drift");
            return;
        }

        if (status == Status.WAITING_FIRST || anchorXpSeed == NONE_SEED) {
            anchorXpSeed = xpSeed;
            status = Status.WAITING_SECOND;
            predictedNextXpSeed = NONE_SEED;
            invalidationReason = "";
            return;
        }

        Long recovered = recoverInternalStateAfterSecond(anchorXpSeed, xpSeed);
        if (recovered == null) {
            anchorXpSeed = xpSeed;
            status = Status.WAITING_SECOND;
            predictedNextXpSeed = NONE_SEED;
            invalidationReason = "recover_failed";
            currentInternalSeed = 0L;
            predictedInternalSeed = 0L;
            return;
        }

        currentInternalSeed = recovered;
        predictedInternalSeed = nextState(currentInternalSeed);
        predictedNextXpSeed = outputFromState(predictedInternalSeed);
        status = Status.PREDICTING;
        invalidationReason = "";
    }

    public static synchronized void invalidatePredictionKeepLastObserved(String reason) {
        if (status != Status.PREDICTING) {
            return;
        }
        predictedNextXpSeed = NONE_SEED;
        currentInternalSeed = 0L;
        predictedInternalSeed = 0L;
        invalidationReason = reason == null ? "invalidated" : reason;
        if (lastObservedXpSeed != NONE_SEED) {
            anchorXpSeed = lastObservedXpSeed;
            status = Status.WAITING_SECOND;
        } else {
            anchorXpSeed = NONE_SEED;
            status = Status.WAITING_FIRST;
        }
    }

    private static Long recoverInternalStateAfterSecond(int firstXpSeed, int secondXpSeed) {
        long firstUpper = Integer.toUnsignedLong(firstXpSeed) << 16;
        Long recovered = null;

        for (int low = 0; low <= 0xFFFF; low++) {
            long stateAfterFirst = firstUpper | (long) low;
            long stateAfterSecond = nextState(stateAfterFirst);
            if (((int) (stateAfterSecond >>> 16)) != secondXpSeed) {
                continue;
            }
            if (recovered != null) {
                return null;
            }
            recovered = stateAfterSecond;
        }

        return recovered;
    }

    private static long nextState(long state) {
        return (state * MULTIPLIER + ADDEND) & MASK;
    }

    private static int outputFromState(long state) {
        return (int) (state >>> 16);
    }

    public static boolean isPredicting() {
        return status == Status.PREDICTING;
    }

    public static String getStatusText() {
        return switch (status) {
            case WAITING_FIRST -> "waiting_first";
            case WAITING_SECOND -> "waiting_second";
            case PREDICTING -> "predicting";
        };
    }

    public static String getAnchorXpSeedText() {
        return formatSeed(anchorXpSeed);
    }

    public static String getLastObservedXpSeedText() {
        return formatSeed(lastObservedXpSeed);
    }

    public static int getPredictedNextXpSeed() {
        return predictedNextXpSeed;
    }

    public static String getPredictedNextXpSeedText() {
        return formatSeed(predictedNextXpSeed);
    }

    public static String getInvalidationReason() {
        return invalidationReason == null ? "" : invalidationReason;
    }

    private static String formatSeed(int value) {
        if (value == NONE_SEED) {
            return "none";
        }
        return Integer.toUnsignedString(value);
    }
}
