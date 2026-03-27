package org.example2.solips;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SeedCrackState {
    public enum Phase {
        IDLE,
        COST_SCAN,
        CLUE_FILTER,
        DONE
    }

    public static final long TOTAL_SEEDS = 0x1_0000_0000L;
    public static final int HINT_FILTER_BITS = 12;
    private static final int UNKNOWN_ENCHANT_SEED = Integer.MIN_VALUE;
    private static final int UNKNOWN_HINT_FILTER = -1;

    private static volatile boolean running = false;
    private static volatile boolean solved = false;
    private static volatile int solvedSeed = 0;
    private static volatile Phase phase = Phase.IDLE;
    private static volatile long checked = 0L;
    private static volatile long phaseTotal = TOTAL_SEEDS;
    private static volatile int matched = 0;
    private static volatile int costMatched = 0;
    private static volatile int resetEpoch = 0;
    private static volatile int trackedEnchantSeed = UNKNOWN_ENCHANT_SEED;
    private static volatile int hintFilterValue = UNKNOWN_HINT_FILTER;
    private static volatile long stopwatchStartNanos = 0L;
    private static volatile long stopwatchEndNanos = 0L;
    private static volatile boolean stopwatchRunning = false;
    private static volatile boolean stopwatchFinished = false;
    private static volatile boolean clueFilterInitialized = false;
    private static volatile boolean costSearchInitialized = false;

    private static final List<ObservationRecord> appliedObservations = new ArrayList<>();
    private static final ArrayDeque<ObservationRecord> queuedObservations = new ArrayDeque<>();
    private static final Set<String> observationKeys = new HashSet<>();
    private static final Set<String> processedCostKeys = new HashSet<>();
    private static final Set<String> processedClueKeys = new HashSet<>();

    private static int[] costCandidates = new int[0];
    private static int[] finalCandidates = new int[0];

    private SeedCrackState() {
    }

    public static synchronized void resetAll() {
        resetEpoch++;
        running = false;
        solved = false;
        solvedSeed = 0;
        phase = Phase.IDLE;
        checked = 0L;
        phaseTotal = TOTAL_SEEDS;
        matched = 0;
        costMatched = 0;
        trackedEnchantSeed = UNKNOWN_ENCHANT_SEED;
        hintFilterValue = UNKNOWN_HINT_FILTER;
        clueFilterInitialized = false;
        costSearchInitialized = false;
        appliedObservations.clear();
        queuedObservations.clear();
        observationKeys.clear();
        processedCostKeys.clear();
        processedClueKeys.clear();
        costCandidates = new int[0];
        finalCandidates = new int[0];
        resetStopwatch();
    }

    private static void resetStopwatch() {
        stopwatchStartNanos = 0L;
        stopwatchEndNanos = 0L;
        stopwatchRunning = false;
        stopwatchFinished = false;
    }

    private static void ensureStopwatchStarted() {
        if (stopwatchStartNanos == 0L) {
            stopwatchStartNanos = System.nanoTime();
            stopwatchEndNanos = 0L;
            stopwatchRunning = true;
            stopwatchFinished = false;
        } else if (!stopwatchFinished && !stopwatchRunning) {
            stopwatchRunning = true;
            stopwatchEndNanos = 0L;
        }
    }

    private static void stopStopwatchIfNeeded() {
        if (stopwatchStartNanos == 0L || stopwatchFinished) {
            return;
        }
        stopwatchEndNanos = System.nanoTime();
        stopwatchRunning = false;
        stopwatchFinished = true;
    }

    public static synchronized boolean updateEnchantSeedAndCheckReset(int currentEnchantSeed) {
        if (currentEnchantSeed == UNKNOWN_ENCHANT_SEED) {
            return false;
        }
        if (trackedEnchantSeed == UNKNOWN_ENCHANT_SEED) {
            trackedEnchantSeed = currentEnchantSeed;
            return false;
        }
        if (trackedEnchantSeed == currentEnchantSeed) {
            return false;
        }
        trackedEnchantSeed = currentEnchantSeed;
        resetAll();
        trackedEnchantSeed = currentEnchantSeed;
        return true;
    }

    public static synchronized void setHintFilterFromSeed(int seed) {
        hintFilterValue = seed & ((1 << HINT_FILTER_BITS) - 1);
    }

    public static synchronized boolean hasHintFilter() {
        return hintFilterValue != UNKNOWN_HINT_FILTER;
    }

    public static synchronized int getHintFilterValue() {
        return hintFilterValue;
    }

    public static synchronized long getHintFilteredSeedCount() {
        return hasHintFilter() ? (1L << (32 - HINT_FILTER_BITS)) : TOTAL_SEEDS;
    }

    public static synchronized boolean addObservationIfAbsent(ObservationRecord observation) {
        if (observation == null) {
            return false;
        }
        if (!observationKeys.add(observation.getKey())) {
            return false;
        }
        queuedObservations.addLast(observation);
        solved = false;
        solvedSeed = 0;
        ensureStopwatchStarted();
        return true;
    }

    public static synchronized boolean hasObservationKey(String key) {
        return key != null && observationKeys.contains(key);
    }

    public static synchronized ObservationRecord activateNextObservation(int expectedEpoch) {
        if (expectedEpoch != resetEpoch) {
            return null;
        }
        ObservationRecord next = queuedObservations.pollFirst();
        if (next != null) {
            appliedObservations.add(next);
            ensureStopwatchStarted();
        }
        return next;
    }

    public static synchronized List<ObservationRecord> getAppliedObservationsSnapshot() {
        return new ArrayList<>(appliedObservations);
    }

    public static synchronized List<ObservationRecord> getPendingClueObservationsSnapshot(int expectedEpoch) {
        if (expectedEpoch != resetEpoch) {
            return List.of();
        }
        List<ObservationRecord> pending = new ArrayList<>();
        for (ObservationRecord record : appliedObservations) {
            if (!processedClueKeys.contains(record.getKey())) {
                pending.add(record);
            }
        }
        return pending;
    }

    public static synchronized boolean hasProcessedCostKey(String costKey) {
        return processedCostKeys.contains(costKey);
    }

    public static synchronized void markCostKeyProcessed(String costKey, int expectedEpoch) {
        if (expectedEpoch == resetEpoch) {
            processedCostKeys.add(costKey);
        }
    }

    public static synchronized boolean hasProcessedClueObservationKey(String key) {
        return processedClueKeys.contains(key);
    }

    public static synchronized void markObservationClueProcessed(String key, int expectedEpoch) {
        if (expectedEpoch == resetEpoch) {
            processedClueKeys.add(key);
        }
    }

    public static synchronized boolean hasQueuedObservations(int expectedEpoch) {
        return expectedEpoch == resetEpoch && !queuedObservations.isEmpty();
    }

    public static synchronized boolean hasQueuedUnprocessedCostObservation(int expectedEpoch) {
        if (expectedEpoch != resetEpoch) {
            return false;
        }
        for (ObservationRecord record : queuedObservations) {
            if (!processedCostKeys.contains(record.getCostKey())) {
                return true;
            }
        }
        return false;
    }

    public static synchronized void beginRun(int expectedEpoch) {
        if (expectedEpoch != resetEpoch) {
            return;
        }
        running = true;
        solved = false;
        solvedSeed = 0;
        phase = Phase.COST_SCAN;
        checked = 0L;
        phaseTotal = TOTAL_SEEDS;
        ensureStopwatchStarted();
    }

    public static synchronized void setCostScanProgress(long newChecked, long total, int currentMatched, int expectedEpoch) {
        if (expectedEpoch != resetEpoch) {
            return;
        }
        phase = Phase.COST_SCAN;
        checked = newChecked;
        phaseTotal = total;
        costMatched = currentMatched;
        matched = finalCandidates.length == 0 ? currentMatched : finalCandidates.length;
    }

    public static synchronized void setCostRefilterProgress(int processed, int total, int currentMatched, int expectedEpoch) {
        if (expectedEpoch != resetEpoch) {
            return;
        }
        phase = Phase.COST_SCAN;
        checked = processed;
        phaseTotal = total;
        costMatched = currentMatched;
        matched = finalCandidates.length == 0 ? currentMatched : finalCandidates.length;
    }

    public static synchronized void beginCluePhase(int total, int expectedEpoch) {
        if (expectedEpoch != resetEpoch) {
            return;
        }
        phase = Phase.CLUE_FILTER;
        checked = 0L;
        phaseTotal = total;
        matched = finalCandidates.length;
    }

    public static synchronized void replaceCostCandidates(int[] candidates, int expectedEpoch) {
        if (expectedEpoch != resetEpoch) {
            return;
        }
        costCandidates = Arrays.copyOf(candidates, candidates.length);
        costMatched = costCandidates.length;
    }

    public static synchronized int[] getCostCandidatesArraySnapshot(int expectedEpoch) {
        if (expectedEpoch != resetEpoch) {
            return new int[0];
        }
        return Arrays.copyOf(costCandidates, costCandidates.length);
    }

    public static synchronized void finishCostPhase(int expectedEpoch) {
        if (expectedEpoch != resetEpoch) {
            return;
        }
        phase = Phase.CLUE_FILTER;
        checked = 0L;
        phaseTotal = costCandidates.length;
        matched = finalCandidates.length;
    }

    public static synchronized boolean isClueFilterInitialized() {
        return clueFilterInitialized;
    }

    public static synchronized boolean isCostSearchInitialized() {
        return costSearchInitialized;
    }

    public static synchronized void markCostSearchInitialized(int expectedEpoch) {
        if (expectedEpoch == resetEpoch) {
            costSearchInitialized = true;
        }
    }

    public static synchronized void markClueFilterInitialized(int expectedEpoch) {
        if (expectedEpoch == resetEpoch) {
            clueFilterInitialized = true;
        }
    }

    public static synchronized void clearFinalCandidates(int expectedEpoch) {
        if (expectedEpoch != resetEpoch) {
            return;
        }
        finalCandidates = new int[0];
        matched = 0;
    }

    public static synchronized void setClueFilterProgress(int processed, int total, int currentMatched, int expectedEpoch) {
        if (expectedEpoch != resetEpoch) {
            return;
        }
        phase = Phase.CLUE_FILTER;
        checked = processed;
        phaseTotal = total;
        matched = currentMatched;
    }

    public static synchronized void replaceFinalCandidates(int[] candidates, int expectedEpoch) {
        if (expectedEpoch != resetEpoch) {
            return;
        }
        finalCandidates = Arrays.copyOf(candidates, candidates.length);
        matched = finalCandidates.length;
    }

    public static synchronized int[] getFinalCandidatesArraySnapshot(int expectedEpoch) {
        if (expectedEpoch != resetEpoch) {
            return new int[0];
        }
        return Arrays.copyOf(finalCandidates, finalCandidates.length);
    }

    public static synchronized void finishObservationRun(int expectedEpoch) {
        if (expectedEpoch != resetEpoch) {
            return;
        }
        running = false;
        phase = Phase.DONE;
        checked = phaseTotal;
        matched = finalCandidates.length;
        costMatched = costCandidates.length;
        solved = finalCandidates.length == 1;
        solvedSeed = solved ? finalCandidates[0] : 0;
        if (solved) {
            stopStopwatchIfNeeded();
        }
    }

    public static synchronized void finishAllRuns(int expectedEpoch) {
        if (expectedEpoch != resetEpoch) {
            return;
        }
        running = false;
        phase = appliedObservations.isEmpty() ? Phase.IDLE : Phase.DONE;
        checked = phase == Phase.IDLE ? 0L : phaseTotal;
        matched = finalCandidates.length;
        costMatched = costCandidates.length;
        solved = finalCandidates.length == 1;
        solvedSeed = solved ? finalCandidates[0] : 0;
        if (solved) {
            stopStopwatchIfNeeded();
        }
    }

    public static synchronized int getResetEpoch() {
        return resetEpoch;
    }

    public static int getObservationCount() {
        return appliedObservations.size() + queuedObservations.size();
    }

    public static int getAppliedObservationCount() {
        return appliedObservations.size();
    }

    public static int getQueuedObservationCount() {
        return queuedObservations.size();
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean isSolved() {
        return solved;
    }

    public static int getSolvedSeed() {
        return solvedSeed;
    }

    public static Phase getPhase() {
        return phase;
    }

    public static long getChecked() {
        return checked;
    }

    public static long getPhaseTotal() {
        return phaseTotal;
    }

    public static int getMatched() {
        return matched;
    }

    public static int getCostMatched() {
        return costMatched;
    }

    public static boolean isStopwatchRunning() {
        return stopwatchRunning;
    }

    public static boolean isStopwatchFinished() {
        return stopwatchFinished;
    }

    public static long getElapsedMillis() {
        long start = stopwatchStartNanos;
        if (start == 0L) {
            return 0L;
        }
        long end = stopwatchRunning ? System.nanoTime() : (stopwatchEndNanos == 0L ? start : stopwatchEndNanos);
        long delta = Math.max(0L, end - start);
        return delta / 1_000_000L;
    }

    public static String getElapsedFormatted() {
        long millis = getElapsedMillis();
        long hours = millis / 3_600_000L;
        long minutes = (millis / 60_000L) % 60L;
        long seconds = (millis / 1_000L) % 60L;
        long ms = millis % 1_000L;
        if (hours > 0L) {
            return String.format("%d:%02d:%02d.%03d", hours, minutes, seconds, ms);
        }
        return String.format("%02d:%02d.%03d", minutes, seconds, ms);
    }
}
