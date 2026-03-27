package org.example2.solips;

import net.minecraft.block.EnchantingTableBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class EnchantHintOverlay {
    private static final int HINT_BITS = 12;
    private static final int PREVIEW_LIMIT = 12;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "SolipsHintOverlay");
            thread.setDaemon(true);
            return thread;
        }
    });

    private static volatile QueryKey runningKey;
    private static volatile FilterResult lastResult;

    private EnchantHintOverlay() {
    }

    public static void render(EnchantmentScreenHandler handler, DrawContext drawContext, int guiLeft, int guiTop) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.textRenderer == null) {
            return;
        }

        ItemStack stack = handler.getSlot(0).getStack();
        if (stack.isEmpty()) {
            drawPanel(drawContext, client, guiLeft, guiTop, List.of("hint filter (12bit)"));
            return;
        }

        Integer bookshelves = resolveBookshelves(client, handler);
        if (bookshelves == null) {
            drawPanel(drawContext, client, guiLeft, guiTop, List.of(
                    "hint filter (12bit)",
                    "bookshelves=(unknown)"
            ));
            return;
        }

        int seed = handler.getSeed();
        int hint = extractLowBits(seed, HINT_BITS);
        int cost0 = handler.enchantmentPower[0];
        int cost1 = handler.enchantmentPower[1];
        int cost2 = handler.enchantmentPower[2];
        int[] observedCosts = {cost0, cost1, cost2};
        int[] rawCostMins = ObservationRecord.buildRawCostMinimums(bookshelves, observedCosts);
        int[] rawCostMaxs = ObservationRecord.buildRawCostMaximums(bookshelves, observedCosts);
        String itemKey = String.valueOf(Registries.ITEM.getId(stack.getItem()));

        QueryKey key = new QueryKey(seed, hint, bookshelves, cost0, cost1, cost2, itemKey);
        if ((lastResult == null || !lastResult.key().equals(key)) && !key.equals(runningKey)) {
            submit(key, stack.copy());
        }

        List<String> lines = new ArrayList<>();
        lines.add("hint filter (low " + HINT_BITS + " bits)");
        lines.add("seed=0x" + toHex(seed));
        lines.add("hint=0x" + String.format("%03X", hint));
        lines.add("bookshelves=" + bookshelves);
        lines.add("costs=[" + cost0 + ", " + cost1 + ", " + cost2 + "]");
        if (hasRawRangeDifference(observedCosts, rawCostMins, rawCostMaxs)) {
            lines.add("raw=[" + formatRange(rawCostMins[0], rawCostMaxs[0]) + ", "
                    + formatRange(rawCostMins[1], rawCostMaxs[1]) + ", "
                    + formatRange(rawCostMins[2], rawCostMaxs[2]) + "]");
        }

        FilterResult result = lastResult;
        if (result != null && result.key().equals(key)) {
            lines.add("candidates=" + result.matchCount() + " (" + result.durationMillis() + " ms)");
            if (result.previewSeeds().isEmpty()) {
                lines.add("preview=(none)");
            } else {
                lines.add("preview:");
                for (int candidate : result.previewSeeds()) {
                    lines.add("  0x" + toHex(candidate));
                }
            }
        } else if (key.equals(runningKey)) {
            lines.add("calculating...");
        } else {
            lines.add("waiting...");
        }

        lines.add("※ low12 + 3 costs only");
        drawPanel(drawContext, client, guiLeft, guiTop, lines);
    }

    private static void submit(QueryKey key, ItemStack stack) {
        runningKey = key;
        EXECUTOR.submit(() -> {
            long started = System.nanoTime();
            FilterResult computed = compute(key, stack, started);
            lastResult = computed;
            if (key.equals(runningKey)) {
                runningKey = null;
            }
        });
    }

    private static FilterResult compute(QueryKey key, ItemStack stack, long startedNanos) {
        List<Integer> preview = new ArrayList<>();
        Random random = Random.create(0L);
        int matches = 0;
        long upperBound = 1L << (32 - HINT_BITS);
        int[] observedCosts = {key.cost0(), key.cost1(), key.cost2()};
        int[] rawCostMins = ObservationRecord.buildRawCostMinimums(key.bookshelves(), observedCosts);
        int[] rawCostMaxs = ObservationRecord.buildRawCostMaximums(key.bookshelves(), observedCosts);

        for (long upper = 0; upper < upperBound; upper++) {
            int candidate = (int) ((upper << HINT_BITS) | (long) key.hint());
            random.setSeed(candidate);

            int c0 = EnchantmentHelper.calculateRequiredExperienceLevel(random, 0, key.bookshelves(), stack);
            if (!matchesRawCost(c0, rawCostMins[0], rawCostMaxs[0])) {
                continue;
            }

            int c1 = EnchantmentHelper.calculateRequiredExperienceLevel(random, 1, key.bookshelves(), stack);
            if (!matchesRawCost(c1, rawCostMins[1], rawCostMaxs[1])) {
                continue;
            }

            int c2 = EnchantmentHelper.calculateRequiredExperienceLevel(random, 2, key.bookshelves(), stack);
            if (!matchesRawCost(c2, rawCostMins[2], rawCostMaxs[2])) {
                continue;
            }

            matches++;
            if (preview.size() < PREVIEW_LIMIT) {
                preview.add(candidate);
            }
        }

        long durationMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
        return new FilterResult(key, matches, List.copyOf(preview), durationMillis);
    }

    private static boolean matchesRawCost(int value, int min, int max) {
        return value >= min && value <= max;
    }

    private static boolean hasRawRangeDifference(int[] observedCosts, int[] rawCostMins, int[] rawCostMaxs) {
        for (int slot = 0; slot < 3; slot++) {
            if (observedCosts[slot] != rawCostMins[slot] || observedCosts[slot] != rawCostMaxs[slot]) {
                return true;
            }
        }
        return false;
    }

    private static String formatRange(int min, int max) {
        return min == max ? Integer.toString(min) : (min + ".." + max);
    }

    private static Integer resolveBookshelves(MinecraftClient client, EnchantmentScreenHandler handler) {
        ScreenHandlerContext context = EnchantmentScreenHandlerUtil.getContext(handler);
        if (context != null) {
            Integer value = context.get((world, pos) -> pos == null ? -1 : countBookshelves(world, pos), -1);
            if (value != null && value >= 0) {
                return value;
            }
        }

        if (ObservedEnchantState.snapshot() != null) {
            return ObservedEnchantState.snapshot().getBookshelves();
        }

        return findNearbyBookshelves(client);
    }

    private static Integer findNearbyBookshelves(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return null;
        }

        BlockPos playerPos = client.player.getBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterate(playerPos.add(-4, -2, -4), playerPos.add(4, 2, 4))) {
            if (!client.world.getBlockState(pos).isOf(net.minecraft.block.Blocks.ENCHANTING_TABLE)) {
                continue;
            }
            double dx = (pos.getX() + 0.5D) - client.player.getX();
            double dy = (pos.getY() + 0.5D) - client.player.getY();
            double dz = (pos.getZ() + 0.5D) - client.player.getZ();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.toImmutable();
            }
        }

        return best == null ? null : countBookshelves(client.world, best);
    }

    private static int countBookshelves(World world, BlockPos tablePos) {
        int count = 0;
        for (BlockPos offset : EnchantingTableBlock.POWER_PROVIDER_OFFSETS) {
            if (EnchantingTableBlock.canAccessPowerProvider(world, tablePos, offset)) {
                count++;
            }
        }
        return Math.min(count, 15);
    }

    private static int extractLowBits(int value, int bits) {
        return value & ((1 << bits) - 1);
    }

    private static void drawPanel(DrawContext drawContext, MinecraftClient client, int guiLeft, int guiTop, List<String> lines) {
        int width = 168;
        int lineHeight = 10;
        int x = guiLeft + 178;
        int y = guiTop + 4;
        int height = 8 + lines.size() * lineHeight;

        if (x + width > client.getWindow().getScaledWidth() - 4) {
            x = Math.max(4, guiLeft - width - 6);
        }

        drawContext.fill(x, y, x + width, y + height, 0xB0000000);
        drawContext.drawBorder(x, y, width, height, 0x80FFFFFF);

        int textY = y + 4;
        for (String line : lines) {
            drawContext.drawText(client.textRenderer, line, x + 4, textY, 0xFFFFFF, false);
            textY += lineHeight;
        }
    }

    private static String toHex(int value) {
        return String.format("%08X", Integer.toUnsignedLong(value));
    }

    private record QueryKey(int seed, int hint, int bookshelves, int cost0, int cost1, int cost2, String itemKey) {
        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof QueryKey other)) {
                return false;
            }
            return seed == other.seed
                    && hint == other.hint
                    && bookshelves == other.bookshelves
                    && cost0 == other.cost0
                    && cost1 == other.cost1
                    && cost2 == other.cost2
                    && Objects.equals(itemKey, other.itemKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(seed, hint, bookshelves, cost0, cost1, cost2, itemKey);
        }
    }

    private record FilterResult(QueryKey key, int matchCount, List<Integer> previewSeeds, long durationMillis) {
    }
}
