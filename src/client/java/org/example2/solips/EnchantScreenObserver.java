package org.example2.solips;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.EnchantmentScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Arrays;

public final class EnchantScreenObserver {
    private static final int TABLE_SEARCH_HORIZONTAL_RADIUS = 6;
    private static final int TABLE_SEARCH_VERTICAL_RADIUS = 4;
    private static final long LOOK_HINT_MAX_AGE_TICKS = 40L;
    private static final double MAX_TABLE_DISTANCE_SQUARED = 64.0D;
    private static final int ITEM_CHANGE_REFRESH_WAIT_TICKS = 8;

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
    private EnchantScreenObserver() {
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
        wasInEnchantScreen = false;
        activeEnchantTablePos = null;
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
    }

    private static void onClientTick(MinecraftClient client) {
        if (!(client.currentScreen instanceof EnchantmentScreen)) {
            updateTableLookHint(client);
        }

        if (!ClientFeatureToggle.isEnabled()) {
            if (wasInEnchantScreen || pendingKey != null || ObservedEnchantState.snapshot() != null) {
                clearClientObservationState();
            }
            return;
        }

        boolean inEnchantScreen = client.currentScreen instanceof EnchantmentScreen;
        if (!inEnchantScreen) {
            if (wasInEnchantScreen) {
                clearClientObservationState();
            }
            wasInEnchantScreen = false;
            return;
        }
        wasInEnchantScreen = true;

        if (client.player == null || !(client.player.currentScreenHandler instanceof EnchantmentScreenHandler menu)) {
            clearClientObservationState();
            return;
        }

        Integer currentEnchantSeed = menu.getSeed();
        boolean enchantSeedReset = false;
        if (currentEnchantSeed != null) {
            enchantSeedReset = SeedCrackState.updateEnchantSeedAndCheckReset(currentEnchantSeed);
            SeedCrackState.setHintFilterFromSeed(currentEnchantSeed);
        }
        if (enchantSeedReset) {
            System.out.println("[seed-reset] newEnchantSeed=" + Integer.toUnsignedString(currentEnchantSeed));
            clearOpenScreenObservationState();
            wasInEnchantScreen = true;
            return;
        }

        if (SeedCrackState.isSolved()) {
            pendingKey = null;
            pendingTicks = 0;
            return;
        }

        ItemStack stack = menu.getSlot(0).getStack();
        if (stack.isEmpty()) {
            clearOpenScreenObservationState();
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
            if (costs[i] > 0 && clueIds[i] >= 0 && clueLevels[i] > 0) {
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

        return null;
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
        count += countBookshelvesAtHeight(world, tablePos, 0);
        count += countBookshelvesAtHeight(world, tablePos, 1);
        return Math.min(count, 15);
    }

    private static int countBookshelvesAtHeight(World world, BlockPos tablePos, int dy) {
        int count = 0;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != 2) {
                    continue;
                }

                BlockPos shelfPos = tablePos.add(dx, dy, dz);
                if (!world.getBlockState(shelfPos).isOf(Blocks.BOOKSHELF)) {
                    continue;
                }

                if (canBookshelfPowerTable(world, tablePos, dy, dx, dz)) {
                    count++;
                }
            }
        }

        return count;
    }

    private static boolean canBookshelfPowerTable(World world, BlockPos tablePos, int dy, int dx, int dz) {
        if (Math.abs(dx) == 2 && Math.abs(dz) == 2) {
            return isPassableGap(world, tablePos.add(dx / 2, dy, dz / 2));
        }
        if (Math.abs(dx) == 2) {
            return isPassableGap(world, tablePos.add(dx / 2, dy, 0));
        }
        if (Math.abs(dz) == 2) {
            return isPassableGap(world, tablePos.add(0, dy, dz / 2));
        }
        return false;
    }

    private static boolean isPassableGap(World world, BlockPos pos) {
        return world.getBlockState(pos).isAir();
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
}
