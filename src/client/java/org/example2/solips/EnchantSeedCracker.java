package org.example2.solips;

import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentLevelEntry;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.Registries;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class EnchantSeedCracker {
    private static final int COST_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    private static final long COST_SCAN_CHUNK_SIZE = 1L << 20;
    private static final int COST_REFILTER_CHUNK_SIZE = 1 << 18;
    private static final int COST_REFILTER_PARALLEL_THRESHOLD = 1 << 17;
    private static final long HINTED_COST_SCAN_CHUNK_SIZE = 1L << 18;

    private static final int CLUE_THREADS = COST_THREADS;
    private static final int CLUE_FILTER_CHUNK_SIZE = 1 << 14;
    private static final int CLUE_FILTER_PARALLEL_THRESHOLD = 1 << 12;
    private static final int CLUE_PROGRESS_FLUSH_INTERVAL = 1 << 12;

    private static final int[] CLUE_SLOT_ORDER = {2, 1, 0};

    private static final Identifier CHANNELING_ID = Identifier.ofVanilla("channeling");
    private static final Identifier BREACH_ID = Identifier.ofVanilla("breach");
    private static final Identifier DEPTH_STRIDER_ID = Identifier.ofVanilla("depth_strider");
    private static final Identifier DENSITY_ID = Identifier.ofVanilla("density");
    private static final Identifier IMPALING_ID = Identifier.ofVanilla("impaling");
    private static final Identifier LOYALTY_ID = Identifier.ofVanilla("loyalty");
    private static final Identifier RIPTIDE_ID = Identifier.ofVanilla("riptide");
    private static final Identifier SWEEPING_EDGE_ID = Identifier.ofVanilla("sweeping_edge");
    private static final Identifier UNBREAKING_ID = Identifier.ofVanilla("unbreaking");
    private static final Identifier WIND_BURST_ID = Identifier.ofVanilla("wind_burst");

    private static volatile Thread worker;
    private static volatile int workerEpoch = Integer.MIN_VALUE;

    private static final ThreadLocal<Random> COST_RANDOM = ThreadLocal.withInitial(() -> Random.create(0L));
    private static final ThreadLocal<ScratchMenuHolder> SCRATCH_MENU = ThreadLocal.withInitial(ScratchMenuHolder::new);

    private static final ExecutorService COST_EXECUTOR = Executors.newFixedThreadPool(COST_THREADS, runnable -> {
        Thread thread = new Thread(runnable, "SolipsCostWorker");
        thread.setDaemon(true);
        return thread;
    });

    private EnchantSeedCracker() {
    }

    private static void debugLog(String event, String detail) {
        System.out.println("[seed-debug] " + event + " " + detail);
    }

    private static final class ScratchMenuHolder {
        private EnchantmentScreenHandler menu;

        private EnchantmentScreenHandler get() {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) {
                menu = null;
                return null;
            }
            if (menu == null) {
                menu = new EnchantmentScreenHandler(0, client.player.getInventory());
            }
            return menu;
        }

        private void clear() {
            menu = null;
        }
    }

    private static final class PreparedObservation {
        final ItemStack stack;
        final int bookshelves;
        final int enchantability;
        final int[] costs;
        final int[] rawCostMins;
        final int[] rawCostMaxs;
        final int[] clueIds;
        final int[] clueLevels;
        final String itemPath;
        final boolean[] ignoredSlots = new boolean[3];
        final boolean[] ignoredClueLevelSlots = new boolean[3];
        final boolean[] shotbowBookUnbreakingSlots = new boolean[3];
        final boolean onShotbow;

        PreparedObservation(ObservationRecord record) {
            this.stack = record.createStack();
            this.bookshelves = record.getBookshelves();
            this.enchantability = this.stack.getItem().getEnchantability();
            this.costs = record.getCosts();
            this.rawCostMins = ObservationRecord.buildRawCostMinimums(this.bookshelves, this.costs);
            this.rawCostMaxs = ObservationRecord.buildRawCostMaximums(this.bookshelves, this.costs);
            this.clueIds = record.getClueIds();
            this.clueLevels = record.getClueLevels();
            Identifier itemKey = Registries.ITEM.getId(this.stack.getItem());
            this.itemPath = itemKey == null ? "" : itemKey.getPath();
            this.onShotbow = ShotbowServerUtil.isOnShotbow(MinecraftClient.getInstance());
        }

        void resolveIgnoredSlots(Registry<Enchantment> enchantmentRegistry) {
            Arrays.fill(ignoredSlots, false);
            Arrays.fill(ignoredClueLevelSlots, false);
            Arrays.fill(shotbowBookUnbreakingSlots, false);
            for (int slot = 0; slot < 3; slot++) {
                if (itemPath.equals("fishing_rod") && costs[slot] <= 9) {
                    ignoredSlots[slot] = true;
                    continue;
                }

                int observedClueId = clueIds[slot];
                if (observedClueId < 0) {
                    continue;
                }

                Enchantment observed = enchantmentRegistry.get(observedClueId);
                if (observed == null) {
                    continue;
                }

                Identifier observedKey = enchantmentRegistry.getId(observed);
                if (observedKey == null) {
                    continue;
                }

                if (onShotbow && itemPath.endsWith("_boots") && DEPTH_STRIDER_ID.equals(observedKey)) {
                    ignoredClueLevelSlots[slot] = true;
                    continue;
                }

                if (onShotbow && itemPath.equals("book") && UNBREAKING_ID.equals(observedKey)) {
                    shotbowBookUnbreakingSlots[slot] = true;
                    if (clueLevels[slot] >= 4) {
                        clueLevels[slot] = 3;
                    }
                    continue;
                }

                if (onShotbow && itemPath.equals("book") && DEPTH_STRIDER_ID.equals(observedKey)) {
                    ignoredClueLevelSlots[slot] = true;
                    continue;
                }

            }
        }
    }

    public static void submitObservation(ObservationRecord observation) {
        if (observation == null) {
            return;
        }
        if (SeedCrackState.isSolved()) {
            debugLog("obs-skip", "reason=already_solved key=" + observation.getKey());
            return;
        }
        if (!SeedCrackState.addObservationIfAbsent(observation)) {
            debugLog("obs-duplicate", "key=" + observation.getKey());
            return;
        }
        debugLog("obs-queued", "bookshelves=" + observation.getBookshelves() + " costs=" + Arrays.toString(observation.getCosts()) + " clueIds=" + Arrays.toString(observation.getClueIds()) + " clueLv=" + Arrays.toString(observation.getClueLevels()));
        ensureWorkerRunning();
    }

    public static void ensureWorkerRunning() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || SeedCrackState.getObservationCount() == 0) {
            return;
        }

        int epoch = SeedCrackState.getResetEpoch();
        synchronized (EnchantSeedCracker.class) {
            if (worker != null && worker.isAlive() && workerEpoch == epoch) {
                return;
            }
            workerEpoch = epoch;
            worker = new Thread(() -> runQueuedCrack(epoch), "EnchantSeedCracker");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void runQueuedCrack(int expectedEpoch) {
        try {
            while (true) {
                if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                    return;
                }

                List<ObservationRecord> activated = drainQueuedObservations(expectedEpoch);
                if (activated.isEmpty()) {
                    SeedCrackState.finishAllRuns(expectedEpoch);
                    return;
                }

                SeedCrackState.beginRun(expectedEpoch);
                debugLog("run-begin", "epoch=" + expectedEpoch + " activated=" + activated.size() + " applied=" + SeedCrackState.getAppliedObservationCount() + " queued=" + SeedCrackState.getQueuedObservationCount());

                while (true) {
                    processAllPendingCostConstraints(expectedEpoch);
                    if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                        return;
                    }

                    List<ObservationRecord> moreActivated = drainQueuedObservations(expectedEpoch);
                    if (moreActivated.isEmpty()) {
                        break;
                    }
                    activated.addAll(moreActivated);
                }

                if (!applyPendingClueConstraints(expectedEpoch)) {
                    continue;
                }

                SeedCrackState.finishObservationRun(expectedEpoch);
                logObservationSummary(activated.get(activated.size() - 1));
            }
        } finally {
            SCRATCH_MENU.get().clear();
            synchronized (EnchantSeedCracker.class) {
                if (Thread.currentThread() == worker) {
                    worker = null;
                }
            }
            if (SeedCrackState.hasQueuedObservations(expectedEpoch)) {
                ensureWorkerRunning();
            }
        }
    }

    private static List<ObservationRecord> drainQueuedObservations(int expectedEpoch) {
        List<ObservationRecord> activated = new ArrayList<>();
        while (true) {
            ObservationRecord next = SeedCrackState.activateNextObservation(expectedEpoch);
            if (next == null) {
                return activated;
            }
            activated.add(next);
        }
    }

    private static void processAllPendingCostConstraints(int expectedEpoch) {
        for (ObservationRecord record : SeedCrackState.getAppliedObservationsSnapshot()) {
            if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                return;
            }
            if (SeedCrackState.hasProcessedCostKey(record.getCostKey())) {
                continue;
            }
            processCostConstraint(record, expectedEpoch);
        }
    }

    private static void processCostConstraint(ObservationRecord record, int expectedEpoch) {
        PreparedObservation prepared = new PreparedObservation(record);
        int[] current = SeedCrackState.getCostCandidatesArraySnapshot(expectedEpoch);
        int[] next;

        debugLog("cost-start", "epoch=" + expectedEpoch + " costKey=" + record.getCostKey() + " current=" + current.length + " initialized=" + SeedCrackState.isCostSearchInitialized());

        if (!SeedCrackState.isCostSearchInitialized()) {
            SeedCrackState.markCostSearchInitialized(expectedEpoch);
            debugLog("cost-full-start", "epoch=" + expectedEpoch + " total=" + SeedCrackState.getHintFilteredSeedCount() + " hint=" + (SeedCrackState.hasHintFilter() ? Integer.toHexString(SeedCrackState.getHintFilterValue()) : "none") + " costKey=" + record.getCostKey());
            next = fullCostScan(prepared, expectedEpoch);
            debugLog("cost-full-end", "epoch=" + expectedEpoch + " matched=" + next.length + " costKey=" + record.getCostKey());
        } else {
            debugLog("cost-refilter-start", "epoch=" + expectedEpoch + " input=" + current.length + " costKey=" + record.getCostKey());
            next = filterByCost(current, prepared, expectedEpoch, record.getCostKey());
            debugLog("cost-refilter-end", "epoch=" + expectedEpoch + " input=" + current.length + " matched=" + next.length + " costKey=" + record.getCostKey());
        }

        if (SeedCrackState.getResetEpoch() != expectedEpoch) {
            return;
        }

        SeedCrackState.replaceCostCandidates(next, expectedEpoch);
        SeedCrackState.markCostKeyProcessed(record.getCostKey(), expectedEpoch);
    }

    private static int[] fullCostScan(PreparedObservation record, int expectedEpoch) {
        IntArrayBuilder matches = new IntArrayBuilder(4096);
        boolean hinted = SeedCrackState.hasHintFilter();
        int hint = hinted ? SeedCrackState.getHintFilterValue() : 0;
        long total = hinted ? SeedCrackState.getHintFilteredSeedCount() : SeedCrackState.TOTAL_SEEDS;
        long chunkSize = hinted ? HINTED_COST_SCAN_CHUNK_SIZE : COST_SCAN_CHUNK_SIZE;
        long nextStart = 0L;

        while (nextStart < total) {
            if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                return new int[0];
            }

            List<Future<CostChunkResult>> futures = new ArrayList<>(COST_THREADS);
            for (int i = 0; i < COST_THREADS && nextStart < total; i++) {
                long start = nextStart;
                long end = Math.min(start + chunkSize, total);
                nextStart = end;
                if (hinted) {
                    futures.add(COST_EXECUTOR.submit(() -> scanHintedCostChunk(record, start, end, hint, expectedEpoch)));
                } else {
                    futures.add(COST_EXECUTOR.submit(() -> scanCostChunk(record, start, end, expectedEpoch)));
                }
            }

            for (Future<CostChunkResult> future : futures) {
                CostChunkResult chunk = await(future);
                if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                    return new int[0];
                }
                matches.addAll(chunk.seeds, chunk.size);
                SeedCrackState.setCostScanProgress(chunk.endCursor, total, matches.size(), expectedEpoch);
            }
        }

        return matches.toArray();
    }

    private static int[] filterByCost(int[] current, PreparedObservation record, int expectedEpoch, String costKey) {
        if (current.length == 0) {
            debugLog("cost-refilter-skip", "epoch=" + expectedEpoch + " input=0 costKey=" + costKey);
            SeedCrackState.setCostRefilterProgress(0, 0, 0, expectedEpoch);
            return new int[0];
        }

        if (COST_THREADS <= 1 || current.length < COST_REFILTER_PARALLEL_THRESHOLD) {
            Random random = COST_RANDOM.get();
            IntArrayBuilder next = new IntArrayBuilder(Math.max(16, current.length / 8));
            int processed = 0;
            for (int seed : current) {
                if (matchesCosts(record, seed, random)) {
                    next.add(seed);
                }
                processed++;
                if ((processed & 0xFFF) == 0 || processed == current.length) {
                    SeedCrackState.setCostRefilterProgress(processed, current.length, next.size(), expectedEpoch);
                }
            }
            return next.toArray();
        }

        IntArrayBuilder merged = new IntArrayBuilder(Math.max(16, current.length / 8));
        int nextIndex = 0;

        while (nextIndex < current.length) {
            List<Future<SeedBatch>> futures = new ArrayList<>(COST_THREADS);
            int submitted = 0;
            for (int i = 0; i < COST_THREADS && nextIndex < current.length; i++) {
                int start = nextIndex;
                int end = Math.min(start + COST_REFILTER_CHUNK_SIZE, current.length);
                nextIndex = end;
                submitted += end - start;
                futures.add(COST_EXECUTOR.submit(() -> filterCostChunk(current, start, end, record)));
            }

            int processed = nextIndex - submitted;
            for (Future<SeedBatch> future : futures) {
                SeedBatch batch = await(future);
                merged.addAll(batch.seeds, batch.size);
                processed += batch.inputCount;
                SeedCrackState.setCostRefilterProgress(processed, current.length, merged.size(), expectedEpoch);
            }
        }

        return merged.toArray();
    }

    private static CostChunkResult scanCostChunk(PreparedObservation record, long start, long end, int expectedEpoch) {
        Random random = COST_RANDOM.get();
        IntArrayBuilder matches = new IntArrayBuilder(256);

        for (long seed = start; seed < end; seed++) {
            if ((seed & 0x3FFFL) == 0L && SeedCrackState.getResetEpoch() != expectedEpoch) {
                break;
            }
            if (matchesCosts(record, (int) seed, random)) {
                matches.add((int) seed);
            }
        }

        return new CostChunkResult(matches.toArray(), matches.size(), end);
    }

    private static CostChunkResult scanHintedCostChunk(PreparedObservation record, long start, long end, int hint, int expectedEpoch) {
        Random random = COST_RANDOM.get();
        IntArrayBuilder matches = new IntArrayBuilder(256);

        for (long upper = start; upper < end; upper++) {
            if ((upper & 0x3FFFL) == 0L && SeedCrackState.getResetEpoch() != expectedEpoch) {
                break;
            }
            int seed = (int) ((upper << SeedCrackState.HINT_FILTER_BITS) | (long) hint);
            if (matchesCosts(record, seed, random)) {
                matches.add(seed);
            }
        }

        return new CostChunkResult(matches.toArray(), matches.size(), end);
    }

    private static SeedBatch filterCostChunk(int[] current, int start, int end, PreparedObservation record) {
        Random random = COST_RANDOM.get();
        IntArrayBuilder next = new IntArrayBuilder(Math.max(32, (end - start) >>> 3));
        for (int i = start; i < end; i++) {
            int seed = current[i];
            if (matchesCosts(record, seed, random)) {
                next.add(seed);
            }
        }
        return new SeedBatch(next.toArray(), next.size());
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException ? (RuntimeException) cause : new RuntimeException(cause);
        }
    }

    private static boolean matchesCosts(PreparedObservation record, int seed, Random random) {
        if (record.enchantability <= 0) {
            for (int slot = 0; slot < 3; slot++) {
                if (!matchesRawCost(record, slot, 0)) {
                    return false;
                }
            }
            return true;
        }

        random.setSeed(seed);

        for (int slot = 0; slot < 3; slot++) {
            int rawCost = EnchantmentHelper.calculateRequiredExperienceLevel(random, slot, record.bookshelves, record.stack);
            if (!matchesRawCost(record, slot, rawCost)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesRawCost(PreparedObservation record, int slot, int rawCost) {
        return rawCost >= record.rawCostMins[slot] && rawCost <= record.rawCostMaxs[slot];
    }

    private static boolean applyPendingClueConstraints(int expectedEpoch) {
        DynamicRegistryManager registryManager = getRegistryManager();
        if (registryManager == null) {
            return false;
        }

        Registry<Enchantment> enchantmentRegistry = registryManager.get(RegistryKeys.ENCHANTMENT);
        if (enchantmentRegistry == null) {
            return false;
        }

        int[] source = SeedCrackState.getCostCandidatesArraySnapshot(expectedEpoch);
        List<ObservationRecord> targets = SeedCrackState.getAppliedObservationsSnapshot();

        debugLog("clue-phase-check", "epoch=" + expectedEpoch + " source=" + source.length + " targets=" + targets.size() + " mode=full_replay");

        if (source.length <= 1) {
            SeedCrackState.replaceFinalCandidates(source, expectedEpoch);
            debugLog("clue-skip", "epoch=" + expectedEpoch + " source=" + source.length + " reason=cost_candidates_leq_1");
            return true;
        }

        for (ObservationRecord record : targets) {
            if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                return false;
            }

            PreparedObservation prepared = new PreparedObservation(record);
            prepared.resolveIgnoredSlots(enchantmentRegistry);
            SeedCrackState.clearFinalCandidates(expectedEpoch);
            SeedCrackState.beginCluePhase(source.length, expectedEpoch);
            debugLog("clue-filter-start", "epoch=" + expectedEpoch + " input=" + source.length + " key=" + record.getKey());

            ClueFilterResult result = filterByClue(source, prepared, registryManager, enchantmentRegistry, expectedEpoch);
            if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                return false;
            }
            if (result.interrupted) {
                debugLog("clue-filter-interrupted", "epoch=" + expectedEpoch + " key=" + record.getKey() + " source=" + source.length + " reason=reset");
                return false;
            }

            source = result.seeds;
            SeedCrackState.replaceFinalCandidates(source, expectedEpoch);
            SeedCrackState.setClueFilterProgress(source.length, Math.max(source.length, 1), source.length, expectedEpoch);
            debugLog("clue-filter-end", "epoch=" + expectedEpoch + " matched=" + source.length + " key=" + record.getKey());
            if (source.length == 0) {
                debugLog("clue-filter-zero", "epoch=" + expectedEpoch + " bookshelves=" + record.getBookshelves() + " costs=" + Arrays.toString(record.getCosts()) + " clueIds=" + Arrays.toString(record.getClueIds()) + " clueLv=" + Arrays.toString(record.getClueLevels()));
            }
        }

        return true;
    }

    private static ClueFilterResult filterByClue(
            int[] source,
            PreparedObservation record,
            DynamicRegistryManager registryManager,
            Registry<Enchantment> enchantmentRegistry,
            int expectedEpoch
    ) {
        if (source.length == 0) {
            return ClueFilterResult.completed(new int[0]);
        }

        if (CLUE_THREADS <= 1 || source.length < CLUE_FILTER_PARALLEL_THRESHOLD) {
            return filterByClueSequential(source, record, registryManager, enchantmentRegistry, expectedEpoch);
        }

        IntArrayBuilder merged = new IntArrayBuilder(Math.max(16, source.length / 8));
        int processed = 0;

        while (processed < source.length) {
            if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                return ClueFilterResult.interrupted();
            }

            List<Future<SeedBatch>> futures = new ArrayList<>(CLUE_THREADS);
            int submitted = 0;
            for (int i = 0; i < CLUE_THREADS && processed < source.length; i++) {
                int start = processed;
                int end = Math.min(start + CLUE_FILTER_CHUNK_SIZE, source.length);
                processed = end;
                submitted += end - start;
                futures.add(COST_EXECUTOR.submit(() -> filterClueChunk(source, start, end, record, registryManager, enchantmentRegistry, expectedEpoch)));
            }

            int batchProcessed = processed - submitted;
            for (Future<SeedBatch> future : futures) {
                SeedBatch batch = await(future);
                if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                    return ClueFilterResult.interrupted();
                }
                merged.addAll(batch.seeds, batch.size);
                batchProcessed += batch.inputCount;
                SeedCrackState.setClueFilterProgress(batchProcessed, source.length, merged.size(), expectedEpoch);
            }
        }

        return ClueFilterResult.completed(merged.toArray());
    }

    private static ClueFilterResult filterByClueSequential(
            int[] source,
            PreparedObservation record,
            DynamicRegistryManager registryManager,
            Registry<Enchantment> enchantmentRegistry,
            int expectedEpoch
    ) {
        IntArrayBuilder next = new IntArrayBuilder(Math.max(16, source.length / 8));
        for (int i = 0; i < source.length; i++) {
            if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                return ClueFilterResult.interrupted();
            }

            int seed = source[i];
            if (matchesClues(record, seed, registryManager, enchantmentRegistry)) {
                next.add(seed);
            }

            if (((i + 1) & (CLUE_PROGRESS_FLUSH_INTERVAL - 1)) == 0 || i + 1 == source.length) {
                SeedCrackState.setClueFilterProgress(i + 1, source.length, next.size(), expectedEpoch);
            }
        }
        return ClueFilterResult.completed(next.toArray());
    }

    private static SeedBatch filterClueChunk(
            int[] source,
            int start,
            int end,
            PreparedObservation record,
            DynamicRegistryManager registryManager,
            Registry<Enchantment> enchantmentRegistry,
            int expectedEpoch
    ) {
        IntArrayBuilder next = new IntArrayBuilder(Math.max(16, (end - start) / 8));
        for (int i = start; i < end; i++) {
            if (((i - start) & 0x3FF) == 0) {
                if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                    break;
                }
            }
            int seed = source[i];
            if (matchesClues(record, seed, registryManager, enchantmentRegistry)) {
                next.add(seed);
            }
        }
        return new SeedBatch(next.toArray(), next.size(), end - start);
    }

    private static boolean matchesClues(
            PreparedObservation record,
            int seed,
            DynamicRegistryManager registryManager,
            Registry<Enchantment> enchantmentRegistry
    ) {
        try {
            EnchantmentScreenHandler menu = getScratchMenu();
            if (menu == null || !setMenuSeed(menu, seed)) {
                return false;
            }

            int[] clueIds = record.clueIds;
            int[] clueLevels = record.clueLevels;
            int[] costs = record.costs;
            ItemStack stack = record.stack;
            boolean[] ignoredSlots = record.ignoredSlots;
            boolean[] ignoredClueLevelSlots = record.ignoredClueLevelSlots;

            for (int slot : CLUE_SLOT_ORDER) {
                if (costs[slot] <= 0 || ignoredSlots[slot]) {
                    continue;
                }

                List<EnchantmentLevelEntry> list = generateEnchantments(menu, registryManager, stack, slot, costs[slot]);
                if (list == null || list.isEmpty()) {
                    return false;
                }

                EnchantmentLevelEntry displayed = pickDisplayedClue(menu, list);
                if (displayed == null) {
                    return false;
                }

                if (!matchesDisplayedClueId(record, slot, enchantmentRegistry, displayed)) {
                    return false;
                }
                int displayedLevel = normalizeDisplayedClueLevel(record, slot, displayed.level);
                if (!ignoredClueLevelSlots[slot] && clueLevels[slot] > 0 && displayedLevel != clueLevels[slot]) {
                    return false;
                }
            }

            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static DynamicRegistryManager getRegistryManager() {
        return EnchantmentScreenHandlerUtil.getRegistryManager(MinecraftClient.getInstance());
    }

    private static EnchantmentScreenHandler getScratchMenu() {
        return SCRATCH_MENU.get().get();
    }

    private static boolean setMenuSeed(EnchantmentScreenHandler menu, int seed) {
        return EnchantmentScreenHandlerUtil.setMenuSeed(menu, seed);
    }

    private static List<EnchantmentLevelEntry> generateEnchantments(
            EnchantmentScreenHandler menu,
            DynamicRegistryManager registryManager,
            ItemStack stack,
            int slot,
            int cost
    ) {
        return EnchantmentScreenHandlerUtil.generateEnchantments(menu, registryManager, stack, slot, cost);
    }

    private static EnchantmentLevelEntry pickDisplayedClue(EnchantmentScreenHandler menu, List<EnchantmentLevelEntry> list) {
        return EnchantmentScreenHandlerUtil.pickDisplayedClue(menu, list);
    }

    private static boolean matchesDisplayedClueId(
            PreparedObservation record,
            int slot,
            Registry<Enchantment> enchantmentRegistry,
            EnchantmentLevelEntry displayed
    ) {
        int observedClueId = record.clueIds[slot];
        if (observedClueId < 0) {
            return true;
        }

        int actualId = remapShotbowSwordClueId(record, enchantmentRegistry, displayed);
        if (actualId == observedClueId) {
            return true;
        }

        if (!record.shotbowBookUnbreakingSlots[slot]) {
            return false;
        }

        Identifier displayedKey = enchantmentRegistry.getId(displayed.enchantment.value());
        return LOYALTY_ID.equals(displayedKey)
                || IMPALING_ID.equals(displayedKey)
                || RIPTIDE_ID.equals(displayedKey)
                || CHANNELING_ID.equals(displayedKey)
                || SWEEPING_EDGE_ID.equals(displayedKey)
                || DENSITY_ID.equals(displayedKey)
                || BREACH_ID.equals(displayedKey)
                || WIND_BURST_ID.equals(displayedKey);
    }

    private static int normalizeDisplayedClueLevel(PreparedObservation record, int slot, int displayedLevel) {
        if (!record.shotbowBookUnbreakingSlots[slot]) {
            return displayedLevel;
        }
        return displayedLevel >= 4 ? 3 : displayedLevel;
    }

    private static int remapShotbowSwordClueId(
            PreparedObservation record,
            Registry<Enchantment> enchantmentRegistry,
            EnchantmentLevelEntry displayed
    ) {
        int actualId = enchantmentRegistry.getRawId(displayed.enchantment.value());
        if (!record.onShotbow || !record.itemPath.endsWith("_sword")) {
            return actualId;
        }

        Identifier displayedKey = enchantmentRegistry.getId(displayed.enchantment.value());
        if (!SWEEPING_EDGE_ID.equals(displayedKey)) {
            return actualId;
        }

        Enchantment unbreaking = enchantmentRegistry.get(UNBREAKING_ID);
        return unbreaking == null ? actualId : enchantmentRegistry.getRawId(unbreaking);
    }

    private static void logObservationSummary(ObservationRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("[item-result] bookshelves=").append(record.getBookshelves())
                .append(" costs=").append(Arrays.toString(record.getCosts()))
                .append(" clueIds=").append(Arrays.toString(record.getClueIds()))
                .append(" clueLv=").append(Arrays.toString(record.getClueLevels()))
                .append(" phase=").append(SeedCrackState.getPhase())
                .append(" checked=").append(SeedCrackState.getChecked())
                .append(" total=").append(SeedCrackState.getPhaseTotal())
                .append(" costMatched=").append(SeedCrackState.getCostMatched())
                .append(" matched=").append(SeedCrackState.getMatched())
                .append(" applied=").append(SeedCrackState.getAppliedObservationCount())
                .append(" queued=").append(SeedCrackState.getQueuedObservationCount())
                .append(" solved=").append(SeedCrackState.isSolved());

        if (SeedCrackState.isSolved()) {
            sb.append(" solvedSeed=").append(Integer.toUnsignedString(SeedCrackState.getSolvedSeed()));
        }

        System.out.println(sb);
    }

    private static final class SeedBatch {
        final int[] seeds;
        final int size;
        final int inputCount;

        private SeedBatch(int[] seeds, int size) {
            this(seeds, size, size);
        }

        private SeedBatch(int[] seeds, int size, int inputCount) {
            this.seeds = seeds;
            this.size = size;
            this.inputCount = inputCount;
        }
    }

    private static final class CostChunkResult {
        final int[] seeds;
        final int size;
        final long endCursor;

        private CostChunkResult(int[] seeds, int size, long endCursor) {
            this.seeds = seeds;
            this.size = size;
            this.endCursor = endCursor;
        }
    }

    private static final class ClueFilterResult {
        final int[] seeds;
        final boolean interrupted;

        private ClueFilterResult(int[] seeds, boolean interrupted) {
            this.seeds = seeds;
            this.interrupted = interrupted;
        }

        private static ClueFilterResult completed(int[] seeds) {
            return new ClueFilterResult(seeds, false);
        }

        private static ClueFilterResult interrupted() {
            return new ClueFilterResult(new int[0], true);
        }
    }

    private static final class IntArrayBuilder {
        private int[] values;
        private int size;

        private IntArrayBuilder(int initialCapacity) {
            this.values = new int[Math.max(1, initialCapacity)];
        }

        private void add(int value) {
            if (size >= values.length) {
                values = Arrays.copyOf(values, Math.max(values.length * 2, size + 1));
            }
            values[size++] = value;
        }

        private void addAll(int[] source, int count) {
            if (count <= 0) {
                return;
            }
            int required = size + count;
            if (required > values.length) {
                int newLength = values.length;
                while (newLength < required) {
                    newLength = Math.max(newLength * 2, required);
                }
                values = Arrays.copyOf(values, newLength);
            }
            System.arraycopy(source, 0, values, size, count);
            size += count;
        }

        private int size() {
            return size;
        }

        private int[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
