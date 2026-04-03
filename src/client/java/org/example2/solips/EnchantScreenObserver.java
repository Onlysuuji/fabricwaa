package org.example2.solips;

import net.minecraft.block.EnchantingTableBlock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.EnchantmentScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.Arrays;

public final class EnchantScreenObserver {
    private static final int TABLE_SEARCH_HORIZONTAL_RADIUS = 6;
    private static final int TABLE_SEARCH_VERTICAL_RADIUS = 4;
    private static final long LOOK_HINT_MAX_AGE_TICKS = 40L;
    private static final double MAX_TABLE_DISTANCE_SQUARED = 64.0D;
    private static final int ITEM_CHANGE_REFRESH_WAIT_TICKS = 8;
    private static final int ENCHANT_SEED_CONFIRM_TICKS = 3;
    private static final int UNKNOWN_ENCHANT_SEED = Integer.MIN_VALUE;

    private static boolean initialized = false;
    private static boolean wasInEnchantScreen = false;
    private static String pendingKey = null;
    private static int pendingTicks = 0;
    private static BlockPos lastLookedEnchantTablePos = null;
    private static long lastLookedEnchantTableTick = Long.MIN_VALUE;
    private static BlockPos activeEnchantTablePos = null;

    private static Item lastSeenItem = null;
    private static String lastRawMenuFingerprint = null;
    private static boolean waitingForFreshMenuAfterItemChange = false;
    private static String itemChangeBaselineMenuFingerprint = null;
    private static int itemChangeWaitTicks = 0;
    private static int pendingEnchantSeed = UNKNOWN_ENCHANT_SEED;
    private static int pendingEnchantSeedTicks = 0;

    private static final int AUTO_PROBE_SETTLE_TICKS = 5;

    private static boolean pendingAutoInsertOnOpen = false;
    private static Item autoProbeCurrentItem = null;
    private static int autoProbeSourceSlot = -1;
    private static int autoProbeSettleTicks = 0;
    private static final java.util.Set<AutoProbeAttemptKey> autoProbeTriedItems = new java.util.HashSet<>();
    private EnchantScreenObserver() {
    }

    private record AutoProbeAttemptKey(String itemKey, int bookshelves) {
    }

    private static AutoProbeAttemptKey createAutoProbeAttemptKey(Item item, int bookshelves) {
        return new AutoProbeAttemptKey(getAutoProbeItemGroupKey(item), bookshelves);
    }

    private static String getAutoProbeItemGroupKey(Item item) {
        Identifier id = Registries.ITEM.getId(item);
        if (id == null) {
            return String.valueOf(item);
        }

        if (item instanceof PickaxeItem || item instanceof AxeItem || item instanceof ShovelItem) {
            String materialKey = stripSharedToolSuffix(id.getPath());
            if (materialKey != null) {
                return id.getNamespace() + ":" + materialKey + "_tool";
            }
        }
        return id.toString();
    }

    private static String stripSharedToolSuffix(String path) {
        if (path.endsWith("_pickaxe")) {
            return path.substring(0, path.length() - "_pickaxe".length());
        }
        if (path.endsWith("_shovel")) {
            return path.substring(0, path.length() - "_shovel".length());
        }
        if (path.endsWith("_axe")) {
            return path.substring(0, path.length() - "_axe".length());
        }
        return null;
    }

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(EnchantScreenObserver::onClientTick);
    }

    public static void clearClientObservationState() {
        clearOpenScreenObservationState();
        clearAutoProbeTransientState();
        wasInEnchantScreen = false;
        activeEnchantTablePos = null;
    }

    public static void resetClientObservationState() {
        clearOpenScreenObservationState();
        clearAutoProbeState();
        wasInEnchantScreen = false;
        activeEnchantTablePos = null;
    }

    public static void onAutoInsertToggleChanged(MinecraftClient client, boolean enabled) {
        if (enabled) {
            if (client != null && client.currentScreen instanceof EnchantmentScreen) {
                pendingAutoInsertOnOpen = true;
            }
            return;
        }

        pendingAutoInsertOnOpen = false;
        if (client == null || client.player == null || client.interactionManager == null) {
            return;
        }
        if (!(client.player.currentScreenHandler instanceof EnchantmentScreenHandler menu)) {
            clearAutoProbeTransientState();
            return;
        }
        if (!client.player.currentScreenHandler.getCursorStack().isEmpty()) {
            return;
        }

        Slot enchantSlot = menu.getSlot(0);
        if (autoProbeCurrentItem != null && !enchantSlot.getStack().isEmpty() && enchantSlot.getStack().getItem() == autoProbeCurrentItem) {
            returnCurrentProbeItem(client, menu);
            return;
        }

        clearAutoProbeTransientState();
    }

    private static void clearScreenCloseState() {
        clearOpenScreenObservationState();
        clearAutoProbeTransientState();
        wasInEnchantScreen = false;
        activeEnchantTablePos = null;
    }

    private static void clearEmptyEnchantSlotObservationState() {
        ObservedEnchantState.clear();
        pendingKey = null;
        pendingTicks = 0;
        lastSeenItem = null;
        waitingForFreshMenuAfterItemChange = false;
        itemChangeBaselineMenuFingerprint = null;
        itemChangeWaitTicks = 0;
    }

    private static void clearOpenScreenObservationState() {
        ObservedEnchantState.clear();
        pendingKey = null;
        pendingTicks = 0;
        lastSeenItem = null;
        lastRawMenuFingerprint = null;
        waitingForFreshMenuAfterItemChange = false;
        itemChangeBaselineMenuFingerprint = null;
        itemChangeWaitTicks = 0;
        pendingEnchantSeed = UNKNOWN_ENCHANT_SEED;
        pendingEnchantSeedTicks = 0;
    }

    private static void clearAutoProbeTransientState() {
        pendingAutoInsertOnOpen = false;
        autoProbeCurrentItem = null;
        autoProbeSourceSlot = -1;
        autoProbeSettleTicks = 0;
    }

    private static void clearAutoProbeState() {
        clearAutoProbeTransientState();
        autoProbeTriedItems.clear();
    }

    private static void onClientTick(MinecraftClient client) {
        if (!(client.currentScreen instanceof EnchantmentScreen)) {
            updateTableLookHint(client);
        }

        if (!ClientFeatureToggle.isEnabled()) {
            if (wasInEnchantScreen || pendingKey != null || ObservedEnchantState.snapshot() != null) {
                resetClientObservationState();
            }
            return;
        }

        boolean inEnchantScreen = client.currentScreen instanceof EnchantmentScreen;
        if (!inEnchantScreen) {
            if (wasInEnchantScreen) {
                clearScreenCloseState();
            }
            wasInEnchantScreen = false;
            return;
        }
        boolean enteredEnchantScreen = !wasInEnchantScreen;
        wasInEnchantScreen = true;

        if (client.player == null) {
            resetClientObservationState();
            return;
        }
        if (!(client.player.currentScreenHandler instanceof EnchantmentScreenHandler menu)) {
            clearScreenCloseState();
            return;
        }
        if (enteredEnchantScreen) {
            pendingAutoInsertOnOpen = true;
        }

        Integer currentEnchantSeed = menu.getSeed();
        boolean enchantSeedReset = false;
        if (isConfirmedEnchantSeed(currentEnchantSeed)) {
            enchantSeedReset = SeedCrackState.updateEnchantSeedAndCheckReset(currentEnchantSeed);
            SeedCrackState.setHintFilterFromSeed(currentEnchantSeed);
        }
        if (enchantSeedReset) {
            System.out.println("[seed-reset] newEnchantSeed=" + Integer.toUnsignedString(currentEnchantSeed));
            clearOpenScreenObservationState();
            clearAutoProbeState();
            wasInEnchantScreen = true;
            return;
        }

        runAutoProbeIfNeeded(client, menu);

        if (SeedCrackState.isSolved()) {
            pendingKey = null;
            pendingTicks = 0;
            return;
        }

        ItemStack stack = menu.getSlot(0).getStack();
        if (stack.isEmpty()) {
            clearEmptyEnchantSlotObservationState();
            return;
        }


        if (lastSeenItem != stack.getItem()) {
            lastSeenItem = stack.getItem();
            waitingForFreshMenuAfterItemChange = true;
            itemChangeBaselineMenuFingerprint = lastRawMenuFingerprint;
            itemChangeWaitTicks = 0;
            pendingKey = null;
            pendingTicks = 0;
        }

        int[] costs = new int[3];
        int[] clueIds = new int[3];
        int[] clueLevels = new int[3];
        boolean usable = false;
        for (int i = 0; i < 3; i++) {
            costs[i] = menu.enchantmentPower[i];
            clueIds[i] = menu.enchantmentId[i];
            clueLevels[i] = menu.enchantmentLevel[i];
            if (isUsableObservationSlot(stack.getItem(), costs[i], clueIds[i], clueLevels[i])) {
                usable = true;
            }
        }
        if (!usable) {
            return;
        }

        String menuFingerprint = buildMenuFingerprint(costs, clueIds, clueLevels);
        lastRawMenuFingerprint = menuFingerprint;

        if (waitingForFreshMenuAfterItemChange) {
            itemChangeWaitTicks++;
            boolean changedFromPreviousItemMenu = itemChangeBaselineMenuFingerprint == null
                    || !menuFingerprint.equals(itemChangeBaselineMenuFingerprint);
            if (!changedFromPreviousItemMenu && itemChangeWaitTicks < ITEM_CHANGE_REFRESH_WAIT_TICKS) {
                return;
            }
            waitingForFreshMenuAfterItemChange = false;
            itemChangeBaselineMenuFingerprint = null;
            itemChangeWaitTicks = 0;
            pendingKey = null;
            pendingTicks = 0;
        }

        Integer resolvedBookshelves = resolveBookshelves(client, menu);
        if (resolvedBookshelves == null) {
            return;
        }

        int bookshelves = normalizeBookshelvesFromObservedCosts(resolvedBookshelves, costs);
        if (bookshelves != resolvedBookshelves) {
            System.out.println("[bookshelf-corrected] raw=" + resolvedBookshelves + " corrected=" + bookshelves
                    + " costs=" + Arrays.toString(costs));
        }

        String key = ObservationRecord.buildKey(stack.getItem(), bookshelves, costs, clueIds, clueLevels);
        if (!key.equals(pendingKey)) {
            pendingKey = key;
            pendingTicks = 1;
            return;
        }

        pendingTicks++;
        if (pendingTicks < 3) {
            return;
        }

        if (isZeroMatchDeadEnd() && !SeedCrackState.hasObservationKey(key)) {
            System.out.println("[dead-end-reset] restarting from new observation key=" + key);
            SeedCrackState.resetAll();
            if (currentEnchantSeed != null) {
                SeedCrackState.updateEnchantSeedAndCheckReset(currentEnchantSeed);
                SeedCrackState.setHintFilterFromSeed(currentEnchantSeed);
            }
        }

        System.out.println("[obs-read] bookshelves=" + bookshelves
                + " costs=" + Arrays.toString(costs)
                + " clueIds=" + Arrays.toString(clueIds)
                + " clueLv=" + Arrays.toString(clueLevels));

        ObservedEnchantState.set(stack.getItem(), bookshelves, costs, clueIds, clueLevels);
        EnchantSeedCracker.submitObservation(new ObservationRecord(stack.getItem(), bookshelves, costs, clueIds, clueLevels));
    }

    private static boolean isZeroMatchDeadEnd() {
        return !SeedCrackState.isRunning()
                && SeedCrackState.getPhase() == SeedCrackState.Phase.DONE
                && SeedCrackState.getMatched() == 0
                && SeedCrackState.getObservationCount() > 0;
    }

    private static boolean isUsableObservationSlot(Item item, int cost, int clueId, int clueLevel) {
        if (cost <= 0) {
            return false;
        }
        if (clueId >= 0 && clueLevel > 0) {
            return true;
        }

        // Fishing rods keep low costs as constraints even when the clue is not usable.
        return item == Items.FISHING_ROD && cost <= 9;
    }

    private static boolean isConfirmedEnchantSeed(Integer currentEnchantSeed) {
        if (currentEnchantSeed == null) {
            pendingEnchantSeed = UNKNOWN_ENCHANT_SEED;
            pendingEnchantSeedTicks = 0;
            return false;
        }

        if (pendingEnchantSeed != currentEnchantSeed) {
            pendingEnchantSeed = currentEnchantSeed;
            pendingEnchantSeedTicks = 1;
            return false;
        }

        if (pendingEnchantSeedTicks < ENCHANT_SEED_CONFIRM_TICKS) {
            pendingEnchantSeedTicks++;
        }
        return pendingEnchantSeedTicks >= ENCHANT_SEED_CONFIRM_TICKS;
    }

    private static String buildMenuFingerprint(int[] costs, int[] clueIds, int[] clueLevels) {
        return costs[0] + "," + costs[1] + "," + costs[2] + "|"
                + clueIds[0] + "," + clueIds[1] + "," + clueIds[2] + "|"
                + clueLevels[0] + "," + clueLevels[1] + "," + clueLevels[2];
    }

    private static void updateTableLookHint(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            return;
        }
        if (client.crosshairTarget instanceof BlockHitResult blockHitResult) {
            BlockPos pos = blockHitResult.getBlockPos();
            if (client.world.getBlockState(pos).isOf(Blocks.ENCHANTING_TABLE)) {
                lastLookedEnchantTablePos = pos.toImmutable();
                lastLookedEnchantTableTick = client.world.getTime();
            }
        }
    }

    private static Integer resolveBookshelves(MinecraftClient client, EnchantmentScreenHandler menu) {
        Integer menuBookshelves = tryResolveMenuBookshelves(menu);
        if (menuBookshelves != null) {
            BlockPos menuTablePos = tryResolveMenuTablePos(menu);
            if (menuTablePos != null) {
                activeEnchantTablePos = menuTablePos;
            }
            return menuBookshelves;
        }

        BlockPos menuTablePos = tryResolveMenuTablePos(menu);
        if (menuTablePos != null) {
            activeEnchantTablePos = menuTablePos;
        }

        if (client.world != null && activeEnchantTablePos != null && isValidEnchantTable(client.world, client.player, activeEnchantTablePos)) {
            return countBookshelvesAtTable(client.world, activeEnchantTablePos);
        }

        if (client.world != null
                && client.player != null
                && lastLookedEnchantTablePos != null
                && (client.world.getTime() - lastLookedEnchantTableTick) <= LOOK_HINT_MAX_AGE_TICKS
                && isValidEnchantTable(client.world, client.player, lastLookedEnchantTablePos)) {
            activeEnchantTablePos = lastLookedEnchantTablePos;
            return countBookshelvesAtTable(client.world, lastLookedEnchantTablePos);
        }

        return tryResolveNearbyClientBookshelves(client);
    }

    private static Integer tryResolveMenuBookshelves(EnchantmentScreenHandler menu) {
        ScreenHandlerContext context = EnchantmentScreenHandlerUtil.getContext(menu);
        if (context == null) {
            return null;
        }
        Integer value = context.get((world, pos) -> pos == null ? -1 : countBookshelvesAtTable(world, pos), -1);
        return value != null && value >= 0 ? value : null;
    }

    private static BlockPos tryResolveMenuTablePos(EnchantmentScreenHandler menu) {
        ScreenHandlerContext context = EnchantmentScreenHandlerUtil.getContext(menu);
        if (context == null) {
            return null;
        }
        return context.get((world, pos) -> pos == null ? null : pos.toImmutable(), null);
    }

    private static Integer tryResolveNearbyClientBookshelves(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            return null;
        }
        BlockPos selectedTable = selectNearbyEnchantTable(client);
        if (selectedTable == null) {
            return null;
        }
        activeEnchantTablePos = selectedTable;
        return countBookshelvesAtTable(client.world, selectedTable);
    }

    private static BlockPos selectNearbyEnchantTable(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            return null;
        }

        if (activeEnchantTablePos != null && isValidEnchantTable(client.world, client.player, activeEnchantTablePos)) {
            return activeEnchantTablePos;
        }

        if (lastLookedEnchantTablePos != null
                && (client.world.getTime() - lastLookedEnchantTableTick) <= LOOK_HINT_MAX_AGE_TICKS
                && isValidEnchantTable(client.world, client.player, lastLookedEnchantTablePos)) {
            return lastLookedEnchantTablePos;
        }

        BlockPos playerPos = client.player.getBlockPos();
        BlockPos bestTable = null;
        double bestDistanceSquared = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterate(
                playerPos.add(-TABLE_SEARCH_HORIZONTAL_RADIUS, -TABLE_SEARCH_VERTICAL_RADIUS, -TABLE_SEARCH_HORIZONTAL_RADIUS),
                playerPos.add(TABLE_SEARCH_HORIZONTAL_RADIUS, TABLE_SEARCH_VERTICAL_RADIUS, TABLE_SEARCH_HORIZONTAL_RADIUS)
        )) {
            if (!client.world.getBlockState(pos).isOf(Blocks.ENCHANTING_TABLE)) {
                continue;
            }
            double distanceSquared = getPlayerDistanceSquared(client.player, pos);
            if (distanceSquared > MAX_TABLE_DISTANCE_SQUARED || distanceSquared >= bestDistanceSquared) {
                continue;
            }
            bestDistanceSquared = distanceSquared;
            bestTable = pos.toImmutable();
        }

        return bestTable;
    }

    private static boolean isValidEnchantTable(World world, PlayerEntity player, BlockPos pos) {
        return world != null
                && player != null
                && pos != null
                && world.getBlockState(pos).isOf(Blocks.ENCHANTING_TABLE)
                && getPlayerDistanceSquared(player, pos) <= MAX_TABLE_DISTANCE_SQUARED;
    }

    private static double getPlayerDistanceSquared(PlayerEntity player, BlockPos pos) {
        double dx = (pos.getX() + 0.5D) - player.getX();
        double dy = (pos.getY() + 0.5D) - player.getY();
        double dz = (pos.getZ() + 0.5D) - player.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static int countBookshelvesAtTable(World world, BlockPos tablePos) {
        int count = 0;
        for (BlockPos offset : EnchantingTableBlock.POWER_PROVIDER_OFFSETS) {
            if (EnchantingTableBlock.canAccessPowerProvider(world, tablePos, offset)) {
                count++;
            }
        }
        return Math.min(count, 15);
    }

    private static int normalizeBookshelvesFromObservedCosts(int bookshelves, int[] costs) {
        if (costs == null || costs.length < 3) {
            return bookshelves;
        }
        if (costs[2] >= 30 && bookshelves < 15) {
            return 15;
        }
        return bookshelves;
    }


    private static void runAutoProbeIfNeeded(MinecraftClient client, EnchantmentScreenHandler menu) {
        if (client.player == null || client.interactionManager == null) {
            return;
        }
        if (!ClientFeatureToggle.isAutoInsertEnabled()) {
            pendingAutoInsertOnOpen = false;
            if (autoProbeCurrentItem != null && client.player.currentScreenHandler.getCursorStack().isEmpty()) {
                Slot enchantSlot = menu.getSlot(0);
                if (!enchantSlot.getStack().isEmpty() && enchantSlot.getStack().getItem() == autoProbeCurrentItem) {
                    returnCurrentProbeItem(client, menu);
                    return;
                }
            }
            clearAutoProbeTransientState();
            return;
        }
        if (!client.player.currentScreenHandler.getCursorStack().isEmpty()) {
            return;
        }

        Slot enchantSlot = menu.getSlot(0);
        if (autoProbeCurrentItem != null) {
            ItemStack current = enchantSlot.getStack();
            if (current.isEmpty() || current.getItem() != autoProbeCurrentItem) {
                autoProbeCurrentItem = null;
                autoProbeSourceSlot = -1;
                autoProbeSettleTicks = 0;
                return;
            }

            if (shouldStopAutoProbe()) {
                returnCurrentProbeItem(client, menu);
                return;
            }

            autoProbeSettleTicks++;
            if (autoProbeSettleTicks < AUTO_PROBE_SETTLE_TICKS) {
                return;
            }

            returnCurrentProbeItem(client, menu);
            return;
        }

        if (!enchantSlot.getStack().isEmpty()) {
            pendingAutoInsertOnOpen = false;
            return;
        }

        if (!pendingAutoInsertOnOpen && autoProbeTriedItems.isEmpty()) {
            return;
        }

        if (shouldStopAutoProbe()) {
            pendingAutoInsertOnOpen = false;
            return;
        }

        Integer currentBookshelves = resolveBookshelves(client, menu);
        int attemptBookshelves = currentBookshelves == null ? -1 : currentBookshelves;

        int nextSlot = findBestEnchantInputSourceSlot(menu, true, attemptBookshelves);
        pendingAutoInsertOnOpen = false;
        if (nextSlot < 0) {
            return;
        }

        ItemStack sourceStack = menu.getSlot(nextSlot).getStack();
        autoProbeCurrentItem = sourceStack.getItem();
        autoProbeSourceSlot = nextSlot;
        autoProbeSettleTicks = 0;
        autoProbeTriedItems.add(createAutoProbeAttemptKey(autoProbeCurrentItem, attemptBookshelves));

        moveProbeItemToEnchantSlot(client, menu, nextSlot);
    }

    private static boolean shouldStopAutoProbe() {
        if (SeedCrackState.getObservationCount() == 0) {
            return false;
        }
        if (SeedCrackState.isRunning() || SeedCrackState.getQueuedObservationCount() > 0) {
            return false;
        }
        if (SeedCrackState.getPhase() != SeedCrackState.Phase.DONE) {
            return false;
        }
        int matched = SeedCrackState.getMatched();
        return matched == 0 || matched == 1;
    }

    private static void returnCurrentProbeItem(MinecraftClient client, EnchantmentScreenHandler menu) {
        if (autoProbeSourceSlot >= 0 && autoProbeSourceSlot < menu.slots.size()) {
            clickSlot(client, menu, 0, 0);
            clickSlot(client, menu, autoProbeSourceSlot, 0);
        }
        autoProbeCurrentItem = null;
        autoProbeSourceSlot = -1;
        autoProbeSettleTicks = 0;
    }

    private static void moveProbeItemToEnchantSlot(MinecraftClient client, EnchantmentScreenHandler menu, int sourceSlot) {
        ItemStack sourceStack = menu.getSlot(sourceSlot).getStack();
        if (sourceStack.getCount() > 1 && sourceStack.getMaxCount() > 1) {
            clickSlot(client, menu, sourceSlot, 0);
            clickSlot(client, menu, 0, 1);
            clickSlot(client, menu, sourceSlot, 0);
            return;
        }

        clickSlot(client, menu, sourceSlot, 0);
        clickSlot(client, menu, 0, 0);
    }

    private static void clickSlot(MinecraftClient client, EnchantmentScreenHandler menu, int slotId, int button) {
        client.interactionManager.clickSlot(menu.syncId, slotId, button, SlotActionType.PICKUP, client.player);
    }

    private static int findBestEnchantInputSourceSlot(EnchantmentScreenHandler menu, boolean skipTriedItems, int bookshelves) {
        int bestSlot = -1;
        int bestPriority = Integer.MAX_VALUE;
        for (int i = 2; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.isOf(Items.FISHING_ROD)) {
                continue;
            }
            if (!stack.isEnchantable() && !stack.isOf(Items.BOOK)) {
                continue;
            }
            if (skipTriedItems && autoProbeTriedItems.contains(createAutoProbeAttemptKey(stack.getItem(), bookshelves))) {
                continue;
            }

            int priority = getAutoProbePriority(stack);
            if (priority < bestPriority) {
                bestPriority = priority;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private static int getAutoProbePriority(ItemStack stack) {
        Item item = stack.getItem();
        if (item == Items.BOOK) {
            return 0;
        }
        if (item instanceof ArmorItem) {
            return 1;
        }
        if (item instanceof SwordItem) {
            return 2;
        }
        if (item instanceof ToolItem || item instanceof MiningToolItem || item == Items.SHEARS || item == Items.FISHING_ROD) {
            return 3;
        }
        return 4;
    }

}
