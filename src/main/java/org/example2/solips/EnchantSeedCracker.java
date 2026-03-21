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
import net.minecraft.screen.Property;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

    private static final int CLUE_THREADS = COST_THREADS;
    private static final int CLUE_FILTER_CHUNK_SIZE = 1 << 14;
    private static final int CLUE_FILTER_PARALLEL_THRESHOLD = 1 << 12;
    private static final int CLUE_PROGRESS_FLUSH_INTERVAL = 1 << 12;

    private static final int[] CLUE_SLOT_ORDER = {2, 1, 0};
    private static final int DEBUG_PREVIEW_LIMIT = 8;

    private static final Identifier DEPTH_STRIDER_ID = Identifier.ofVanilla("depth_strider");
    private static final Identifier UNBREAKING_ID = Identifier.ofVanilla("unbreaking");

    private static volatile Thread worker;
    private static volatile int workerEpoch = Integer.MIN_VALUE;

    private static volatile Method reflectedGenerateEnchantments;
    private static volatile Field reflectedSeedField;
    private static volatile Field reflectedRandomField;

    private static final ThreadLocal<Random> COST_RANDOM = ThreadLocal.withInitial(() -> Random.create(0L));
    private static final ThreadLocal<ScratchMenuHolder> SCRATCH_MENU = ThreadLocal.withInitial(ScratchMenuHolder::new);

    private static final ExecutorService COST_EXECUTOR = Executors.newFixedThreadPool(COST_THREADS, runnable -> {
        Thread thread = new Thread(runnable, "SolipsCostWorker");
        thread.setDaemon(true);
        return thread;
    });

    private EnchantSeedCracker() {
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
        final int[] clueIds;
        final int[] clueLevels;
        final String itemPath;
        final boolean[] ignoredSlots = new boolean[3];

        PreparedObservation(ObservationRecord record) {
            this.stack = record.createStack();
            this.bookshelves = record.getBookshelves();
            this.enchantability = this.stack.getItem().getEnchantability();
            this.costs = record.getCosts();
            this.clueIds = record.getClueIds();
            this.clueLevels = record.getClueLevels();
            Identifier itemKey = Registries.ITEM.getId(this.stack.getItem());
            this.itemPath = itemKey == null ? "" : itemKey.getPath();
        }

        void resolveIgnoredSlots(Registry<Enchantment> enchantmentRegistry) {
            Arrays.fill(ignoredSlots, false);
            for (int slot = 0; slot < 3; slot++) {
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

                if (itemPath.endsWith("_boots") && DEPTH_STRIDER_ID.equals(observedKey)) {
                    ignoredSlots[slot] = true;
                    continue;
                }

                if(itemPath.equals("book") && (UNBREAKING_ID.equals(observedKey) || DEPTH_STRIDER_ID.equals(observedKey))){
                    ignoredSlots[slot] = true;
                    continue;
                }

                if (itemPath.endsWith("_sword") && UNBREAKING_ID.equals(observedKey)) {
                    ignoredSlots[slot] = true;
                }
            }
        }
    }

    public static void submitObservation(ObservationRecord observation) {
        if (observation == null) {
            return;
        }
        if (!SeedCrackState.addObservationIfAbsent(observation)) {
            debug("obs-duplicate", "key=" + observation.getKey());
            return;
        }
        debug("obs-queued", "item=" + observation.getItem()
                + " bookshelves=" + observation.getBookshelves()
                + " costs=" + Arrays.toString(observation.getCosts())
                + " clueIds=" + Arrays.toString(observation.getClueIds())
                + " clueLv=" + Arrays.toString(observation.getClueLevels())
                + " epoch=" + SeedCrackState.getResetEpoch());
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
                debug("run-begin", "epoch=" + expectedEpoch
                        + " activated=" + activated.size()
                        + " applied=" + SeedCrackState.getAppliedObservationCount()
                        + " queued=" + SeedCrackState.getQueuedObservationCount());

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

        if (!SeedCrackState.isCostSearchInitialized()) {
            debug("cost-full-start", "epoch=" + expectedEpoch
                    + " costKey=" + record.getCostKey()
                    + " item=" + record.getItem()
                    + " bookshelves=" + record.getBookshelves()
                    + " costs=" + Arrays.toString(record.getCosts()));
            next = fullCostScan(prepared, expectedEpoch);
            debug("cost-full-end", "epoch=" + expectedEpoch
                    + " costKey=" + record.getCostKey()
                    + " matched=" + next.length);
        } else {
            debug("cost-refilter-start", "epoch=" + expectedEpoch
                    + " costKey=" + record.getCostKey()
                    + " input=" + current.length
                    + " bookshelves=" + record.getBookshelves()
                    + " costs=" + Arrays.toString(record.getCosts()));
            next = filterByCost(current, prepared, expectedEpoch);
            debug("cost-refilter-end", "epoch=" + expectedEpoch
                    + " costKey=" + record.getCostKey()
                    + " input=" + current.length
                    + " matched=" + next.length);
        }

        if (SeedCrackState.getResetEpoch() != expectedEpoch) {
            return;
        }

        SeedCrackState.replaceCostCandidates(next, expectedEpoch);
        SeedCrackState.finishCostPhase(expectedEpoch);
        SeedCrackState.markCostKeyProcessed(record.getCostKey(), expectedEpoch);
        debug("cost-commit", "epoch=" + expectedEpoch
                + " costKey=" + record.getCostKey()
                + " costMatched=" + SeedCrackState.getCostMatched());
    }

    private static int[] fullCostScan(PreparedObservation record, int expectedEpoch) {
        IntArrayBuilder matches = new IntArrayBuilder(4096);
        long nextStart = 0L;

        while (nextStart < SeedCrackState.TOTAL_SEEDS) {
            if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                return new int[0];
            }

            List<Future<CostChunkResult>> futures = new ArrayList<>(COST_THREADS);
            for (int i = 0; i < COST_THREADS && nextStart < SeedCrackState.TOTAL_SEEDS; i++) {
                long start = nextStart;
                long end = Math.min(start + COST_SCAN_CHUNK_SIZE, SeedCrackState.TOTAL_SEEDS);
                nextStart = end;
                futures.add(COST_EXECUTOR.submit(() -> scanCostChunk(record, start, end, expectedEpoch)));
            }

            for (Future<CostChunkResult> future : futures) {
                CostChunkResult chunk = await(future);
                if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                    return new int[0];
                }
                matches.addAll(chunk.seeds, chunk.size);
                SeedCrackState.setCostScanProgress(chunk.endCursor, matches.size(), expectedEpoch);
            }
        }

        return matches.toArray();
    }

    private static int[] filterByCost(int[] current, PreparedObservation record, int expectedEpoch) {
        if (current.length == 0) {
            debug("cost-refilter-skip", "epoch=" + expectedEpoch + " reason=empty-input");
            return new int[0];
        }

        if (COST_THREADS <= 1 || current.length < COST_REFILTER_PARALLEL_THRESHOLD) {
            Random random = COST_RANDOM.get();
            IntArrayBuilder next = new IntArrayBuilder(Math.max(16, current.length / 8));
            for (int i = 0; i < current.length; i++) {
                int seed = current[i];
                if (matchesCosts(record, seed, random)) {
                    next.add(seed);
                }
                if (((i + 1) & (CLUE_PROGRESS_FLUSH_INTERVAL - 1)) == 0 || i + 1 == current.length) {
                    SeedCrackState.setCostScanProgress(i + 1, next.size(), expectedEpoch);
                }
            }
            return next.toArray();
        }

        IntArrayBuilder merged = new IntArrayBuilder(Math.max(16, current.length / 8));
        int nextIndex = 0;

        while (nextIndex < current.length) {
            List<Future<SeedBatch>> futures = new ArrayList<>(COST_THREADS);
            for (int i = 0; i < COST_THREADS && nextIndex < current.length; i++) {
                int start = nextIndex;
                int end = Math.min(start + COST_REFILTER_CHUNK_SIZE, current.length);
                nextIndex = end;
                futures.add(COST_EXECUTOR.submit(() -> filterCostChunk(current, start, end, record)));
            }

            for (Future<SeedBatch> future : futures) {
                SeedBatch batch = await(future);
                merged.addAll(batch.seeds, batch.size);
                SeedCrackState.setCostScanProgress(Math.min(nextIndex, current.length), merged.size(), expectedEpoch);
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
            return record.costs[0] == 0 && record.costs[1] == 0 && record.costs[2] == 0;
        }

        random.setSeed(seed);

        for (int slot = 0; slot < 3; slot++) {
            int cost = EnchantmentHelper.calculateRequiredExperienceLevel(random, slot, record.bookshelves, record.stack);
            if (cost < slot + 1) {
                cost = 0;
            }
            if (cost != record.costs[slot]) {
                return false;
            }
        }
        return true;
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

        int[] source = SeedCrackState.isClueFilterInitialized()
                ? SeedCrackState.getFinalCandidatesArraySnapshot(expectedEpoch)
                : SeedCrackState.getCostCandidatesArraySnapshot(expectedEpoch);

        List<ObservationRecord> targets = SeedCrackState.isClueFilterInitialized()
                ? SeedCrackState.getPendingClueObservationsSnapshot(expectedEpoch)
                : SeedCrackState.getAppliedObservationsSnapshot();

        debug("clue-phase-start", "epoch=" + expectedEpoch
                + " source=" + source.length
                + " targets=" + targets.size()
                + " initialized=" + SeedCrackState.isClueFilterInitialized());

        for (ObservationRecord record : targets) {
            if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                return false;
            }
            if (SeedCrackState.hasProcessedClueObservationKey(record.getKey())) {
                continue;
            }

            PreparedObservation prepared = new PreparedObservation(record);
            prepared.resolveIgnoredSlots(enchantmentRegistry);
            debug("clue-filter-start", "epoch=" + expectedEpoch
                    + " key=" + record.getKey()
                    + " input=" + source.length
                    + " item=" + record.getItem()
                    + " bookshelves=" + record.getBookshelves()
                    + " costs=" + Arrays.toString(record.getCosts())
                    + " clueIds=" + Arrays.toString(record.getClueIds())
                    + " clueLv=" + Arrays.toString(record.getClueLevels()));
            SeedCrackState.clearFinalCandidates(expectedEpoch);

            ClueFilterResult result = filterByClue(source, prepared, registryManager, enchantmentRegistry, expectedEpoch);
            if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                return false;
            }
            if (result.interrupted) {
                if (SeedCrackState.isClueFilterInitialized()) {
                    SeedCrackState.replaceFinalCandidates(source, expectedEpoch);
                }
                return false;
            }

            int[] previousSource = source;
            source = result.seeds;
            SeedCrackState.replaceFinalCandidates(source, expectedEpoch);
            SeedCrackState.setClueFilterProgress(source.length, Math.max(source.length, 1), source.length, expectedEpoch);
            if (source.length == 0) {
                debug("clue-filter-zero", "epoch=" + expectedEpoch
                        + " key=" + record.getKey()
                        + " input=" + previousSource.length);
                logClueDiagnostics(record, prepared, previousSource, registryManager, enchantmentRegistry);
            } else {
                debug("clue-filter-end", "epoch=" + expectedEpoch
                        + " key=" + record.getKey()
                        + " matched=" + source.length);
            }
            SeedCrackState.markObservationClueProcessed(record.getKey(), expectedEpoch);
            SeedCrackState.markClueFilterInitialized(expectedEpoch);
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
            if (SeedCrackState.hasQueuedUnprocessedCostObservation(expectedEpoch)) {
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
                if (SeedCrackState.hasQueuedUnprocessedCostObservation(expectedEpoch)) {
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
            if (SeedCrackState.hasQueuedUnprocessedCostObservation(expectedEpoch)) {
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
                if (SeedCrackState.getResetEpoch() != expectedEpoch || SeedCrackState.hasQueuedUnprocessedCostObservation(expectedEpoch)) {
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

                int actualId = enchantmentRegistry.getRawId(displayed.enchantment.value());
                if (clueIds[slot] >= 0 && actualId != clueIds[slot]) {
                    return false;
                }
                if (clueLevels[slot] > 0 && displayed.level != clueLevels[slot]) {
                    return false;
                }
            }

            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static DynamicRegistryManager getRegistryManager() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            return client.world.getRegistryManager();
        }
        if (client.isIntegratedServerRunning() && client.getServer() != null) {
            ServerPlayerEntity serverPlayer = client.player == null ? null : client.getServer().getPlayerManager().getPlayer(client.player.getUuid());
            if (serverPlayer != null) {
                return serverPlayer.getWorld().getRegistryManager();
            }
        }
        return null;
    }

    private static EnchantmentScreenHandler getScratchMenu() {
        return SCRATCH_MENU.get().get();
    }

    private static boolean setMenuSeed(EnchantmentScreenHandler menu, int seed) {
        try {
            Field field = reflectedSeedField;
            if (field == null) {
                field = EnchantmentScreenHandler.class.getDeclaredField("seed");
                field.setAccessible(true);
                reflectedSeedField = field;
            }
            Property property = (Property) field.get(menu);
            property.set(seed);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<EnchantmentLevelEntry> generateEnchantments(
            EnchantmentScreenHandler menu,
            DynamicRegistryManager registryManager,
            ItemStack stack,
            int slot,
            int cost
    ) {
        try {
            Method method = reflectedGenerateEnchantments;
            if (method == null) {
                method = EnchantmentScreenHandler.class.getDeclaredMethod(
                        "generateEnchantments",
                        DynamicRegistryManager.class,
                        ItemStack.class,
                        int.class,
                        int.class
                );
                method.setAccessible(true);
                reflectedGenerateEnchantments = method;
            }
            return (List<EnchantmentLevelEntry>) method.invoke(menu, registryManager, stack, slot, cost);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static EnchantmentLevelEntry pickDisplayedClue(EnchantmentScreenHandler menu, List<EnchantmentLevelEntry> list) {
        if (list.isEmpty()) {
            return null;
        }
        try {
            Field field = reflectedRandomField;
            if (field == null) {
                field = EnchantmentScreenHandler.class.getDeclaredField("random");
                field.setAccessible(true);
                reflectedRandomField = field;
            }
            Random random = (Random) field.get(menu);
            return list.get(random.nextInt(list.size()));
        } catch (ReflectiveOperationException e) {
            return list.get(0);
        }
    }

    private static void debug(String tag, String message) {
        System.out.println("[seed-debug/" + tag + "] " + message);
    }

    private static void logClueDiagnostics(
            ObservationRecord record,
            PreparedObservation prepared,
            int[] source,
            DynamicRegistryManager registryManager,
            Registry<Enchantment> enchantmentRegistry
    ) {
        int previewCount = Math.min(source.length, DEBUG_PREVIEW_LIMIT);
        debug("clue-diagnostics", "item=" + record.getItem()
                + " bookshelves=" + record.getBookshelves()
                + " source=" + source.length
                + " preview=" + previewCount);

        for (int i = 0; i < previewCount; i++) {
            int seed = source[i];
            StringBuilder sb = new StringBuilder();
            sb.append("seed=").append(Integer.toUnsignedString(seed));
            for (int slot : CLUE_SLOT_ORDER) {
                sb.append(" slot").append(slot + 1);
                if (prepared.costs[slot] <= 0) {
                    sb.append("=cost0");
                    continue;
                }
                if (prepared.ignoredSlots[slot]) {
                    sb.append("=ignored");
                    continue;
                }
                CluePreview preview = previewClue(prepared, seed, registryManager, enchantmentRegistry, slot);
                sb.append(" obs=").append(prepared.clueIds[slot]).append('/').append(prepared.clueLevels[slot]);
                if (preview == null) {
                    sb.append(" pred=null");
                } else {
                    sb.append(" pred=").append(preview.id).append('/').append(preview.level);
                }
            }
            debug("clue-preview", sb.toString());
        }
    }

    private static CluePreview previewClue(
            PreparedObservation record,
            int seed,
            DynamicRegistryManager registryManager,
            Registry<Enchantment> enchantmentRegistry,
            int slot
    ) {
        try {
            EnchantmentScreenHandler menu = getScratchMenu();
            if (menu == null || !setMenuSeed(menu, seed)) {
                return null;
            }
            List<EnchantmentLevelEntry> list = generateEnchantments(menu, registryManager, record.stack, slot, record.costs[slot]);
            if (list == null || list.isEmpty()) {
                return null;
            }
            EnchantmentLevelEntry displayed = pickDisplayedClue(menu, list);
            if (displayed == null) {
                return null;
            }
            int actualId = enchantmentRegistry.getRawId(displayed.enchantment.value());
            return new CluePreview(actualId, displayed.level);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void logObservationSummary(ObservationRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("[item-result] item=").append(record.getItem())
                .append(" bookshelves=").append(record.getBookshelves())
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

    private static final class CluePreview {
        final int id;
        final int level;

        private CluePreview(int id, int level) {
            this.id = id;
            this.level = level;
        }
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
