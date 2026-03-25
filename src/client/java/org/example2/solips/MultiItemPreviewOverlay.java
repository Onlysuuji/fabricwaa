package org.example2.solips;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.EnchantmentLevelEntry;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.util.math.random.Random;

import java.util.List;

public final class MultiItemPreviewOverlay {
    private static final Item REFERENCE_ITEM = Items.DIAMOND_PICKAXE;
    private static final Item[] PREVIEW_ITEMS = new Item[] {
            Items.IRON_PICKAXE,
            Items.GOLDEN_PICKAXE,
            Items.DIAMOND_SWORD,
            Items.IRON_SWORD,
            Items.GOLDEN_SWORD,
            Items.DIAMOND_HELMET,
            Items.DIAMOND_CHESTPLATE,
            Items.DIAMOND_LEGGINGS,
            Items.DIAMOND_BOOTS,
            Items.IRON_HELMET,
            Items.IRON_CHESTPLATE,
            Items.IRON_LEGGINGS,
            Items.IRON_BOOTS,
            Items.GOLDEN_BOOTS,
            Items.BOW,
            Items.BOOK
    };

    private static final int CELL_WIDTH = 22;
    private static final int CELL_HEIGHT = 20;
    private static final int GRID_MARGIN = 4;
    private static final int PREVIEW_COLUMN_COUNT = 6;
    private static final int GRID_COLUMNS = 1 + PREVIEW_COLUMN_COUNT;
    private static final int GRID_ROWS = 2 + PREVIEW_ITEMS.length;
    private static final int GRID_WIDTH = GRID_COLUMNS * CELL_WIDTH;
    private static final int GRID_HEIGHT = GRID_ROWS * CELL_HEIGHT;

    private static final GridCache SOLVED_GRID = new GridCache();
    private static boolean initialized = false;

    private static final ThreadLocal<ScratchMenuHolder> SCRATCH_MENU = ThreadLocal.withInitial(ScratchMenuHolder::new);

    private MultiItemPreviewOverlay() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        clearPreviewCache();
        HudRenderCallback.EVENT.register(MultiItemPreviewOverlay::onHudRender);
    }

    private static void onHudRender(DrawContext drawContext, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || !ClientFeatureToggle.isEnabled() || !ClientFeatureToggle.isHudVisible()) {
            return;
        }

        renderLeftHud(client, drawContext, client.textRenderer);

        if (SeedCrackState.isSolved()) {
            renderRightTopGrid(client, drawContext, client.textRenderer);
        }
    }

    private static void renderLeftHud(MinecraftClient client, DrawContext drawContext, TextRenderer font) {
        int left = 5;
        int y = 5;

        drawContext.drawText(font, "hudKey=M", left, y, 0xAAAAAA, false);
        y += font.fontHeight + 2;
        drawContext.drawText(font, "toggleKey=N", left, y, 0xAAAAAA, false);
        y += font.fontHeight + 2;
        drawContext.drawText(font, "observations=" + SeedCrackState.getObservationCount(), left, y, 0xFFFFFF, false);
        y += font.fontHeight + 2;
        drawContext.drawText(font, "matched=" + SeedCrackState.getMatched(), left, y, 0xFFFFFF, false);
        y += font.fontHeight + 2;

        if (SeedCrackState.isSolved()) {
            drawContext.drawText(font, "solvedSeed=" + Integer.toUnsignedString(SeedCrackState.getSolvedSeed()), left, y, 0x55FF55, false);
            y += font.fontHeight + 2;
        }
    }

    private static void renderRightTopGrid(MinecraftClient client, DrawContext drawContext, TextRenderer font) {
        int solvedSeed = SeedCrackState.isSolved() ? SeedCrackState.getSolvedSeed() : Integer.MIN_VALUE;
        if (solvedSeed == Integer.MIN_VALUE) {
            return;
        }

        rebuildGridCacheIfNeeded(client, SOLVED_GRID, solvedSeed);

        int startY = GRID_MARGIN;
        int startX = client.getWindow().getScaledWidth() - GRID_WIDTH - GRID_MARGIN;
        renderGrid(drawContext, font, SOLVED_GRID, startX, startY);
    }

    private static void renderGrid(DrawContext drawContext, TextRenderer font, GridCache cache, int startX, int startY) {
        drawCentered(drawContext, font, "", startX, startY, CELL_WIDTH, CELL_HEIGHT, 0xFFFFFF);
        for (int col = 0; col < PREVIEW_COLUMN_COUNT; col++) {
            drawCentered(drawContext, font, cache.headerLabels[col], startX + CELL_WIDTH * (col + 1), startY, CELL_WIDTH, CELL_HEIGHT, 0xFFFFFF);
        }

        renderPreviewRow(drawContext, cache, startX, startY + CELL_HEIGHT, REFERENCE_ITEM, 0);
        for (int row = 0; row < PREVIEW_ITEMS.length; row++) {
            renderPreviewRow(drawContext, cache, startX, startY + (row + 2) * CELL_HEIGHT, PREVIEW_ITEMS[row], row + 1);
        }
    }

    private static void renderPreviewRow(
            DrawContext drawContext,
            GridCache cache,
            int startX,
            int y,
            Item rowItem,
            int cacheRowIndex
    ) {
        drawContext.drawItem(new ItemStack(rowItem), startX + 3, y + 2);

        for (int col = 0; col < PREVIEW_COLUMN_COUNT; col++) {
            ItemStack stack = cache.previewStacks[cacheRowIndex][col];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            int itemX = startX + (col + 1) * CELL_WIDTH + 3;
            int itemY = y + 2;
            drawContext.drawItem(stack, itemX, itemY);
        }
    }

    private static void rebuildGridCacheIfNeeded(MinecraftClient client, GridCache cache, int seed) {
        if (seed == Integer.MIN_VALUE) {
            clearGridCache(cache);
            return;
        }
        if (cache.cachedSeed == seed) {
            return;
        }

        cache.cachedSeed = seed;
        fillHeaderLabels(cache, seed);
        fillPreviewRow(client, cache, seed, REFERENCE_ITEM, 0);
        for (int i = 0; i < PREVIEW_ITEMS.length; i++) {
            fillPreviewRow(client, cache, seed, PREVIEW_ITEMS[i], i + 1);
        }
    }

    private static void clearPreviewCache() {
        clearGridCache(SOLVED_GRID);
    }

    private static void clearGridCache(GridCache cache) {
        cache.cachedSeed = Integer.MIN_VALUE;
        for (int row = 0; row < cache.previewStacks.length; row++) {
            for (int col = 0; col < PREVIEW_COLUMN_COUNT; col++) {
                cache.previewStacks[row][col] = ItemStack.EMPTY;
            }
        }
        for (int i = 0; i < PREVIEW_COLUMN_COUNT; i++) {
            cache.headerLabels[i] = "0";
        }
    }

    private static void fillHeaderLabels(GridCache cache, int seed) {
        int[] bookshelves0 = calculateSlotCosts(seed, 0, new ItemStack(REFERENCE_ITEM));
        int[] bookshelves15 = calculateSlotCosts(seed, 15, new ItemStack(REFERENCE_ITEM));

        for (int slot = 0; slot < 3; slot++) {
            cache.headerLabels[slot] = Integer.toString(bookshelves0[slot]);
            cache.headerLabels[slot + 3] = Integer.toString(bookshelves15[slot]);
        }
    }

    private static void fillPreviewRow(MinecraftClient client, GridCache cache, int seed, Item item, int rowIndex) {
        for (int col = 0; col < PREVIEW_COLUMN_COUNT; col++) {
            int bookshelves = col < 3 ? 0 : 15;
            int slot = col % 3;
            cache.previewStacks[rowIndex][col] = buildPreviewStack(client, seed, item, bookshelves, slot);
        }
    }

    private static ItemStack buildPreviewStack(MinecraftClient client, int seed, Item item, int bookshelves, int slot) {
        ItemStack inputStack = new ItemStack(item);
        int[] costs = calculateSlotCosts(seed, bookshelves, inputStack);
        int cost = costs[slot];

        ItemStack displayStack = new ItemStack(item);
        if (cost <= 0) {
            return displayStack;
        }

        List<EnchantmentLevelEntry> entries = generateEnchantments(client, seed, inputStack, slot, cost);
        if (entries == null || entries.isEmpty()) {
            return displayStack;
        }

        if (item == Items.BOOK) {
            displayStack = new ItemStack(Items.ENCHANTED_BOOK);
        }

        for (EnchantmentLevelEntry entry : entries) {
            displayStack.addEnchantment(entry.enchantment, entry.level);
        }
        return displayStack;
    }

    private static int[] calculateSlotCosts(int seed, int bookshelves, ItemStack stack) {
        int[] result = new int[3];
        Random random = Random.create(seed);

        for (int slot = 0; slot < 3; slot++) {
            int cost = EnchantmentHelper.calculateRequiredExperienceLevel(random, slot, bookshelves, stack);
            result[slot] = cost < slot + 1 ? 0 : cost;
        }

        return result;
    }

    private static List<EnchantmentLevelEntry> generateEnchantments(
            MinecraftClient client,
            int seed,
            ItemStack stack,
            int slot,
            int cost
    ) {
        DynamicRegistryManager registryManager = EnchantmentScreenHandlerUtil.getRegistryManager(client);
        EnchantmentScreenHandler menu = SCRATCH_MENU.get().get();

        if (registryManager == null || menu == null || !EnchantmentScreenHandlerUtil.setMenuSeed(menu, seed)) {
            return null;
        }
        return EnchantmentScreenHandlerUtil.generateEnchantments(menu, registryManager, stack, slot, cost);
    }

    private static void drawCentered(DrawContext drawContext, TextRenderer font, String text, int x, int y, int width, int height, int color) {
        int drawX = x + (width - font.getWidth(text)) / 2;
        int drawY = y + (height - font.fontHeight) / 2;
        drawContext.drawText(font, text, drawX, drawY, color, false);
    }

    private static final class GridCache {
        private int cachedSeed = Integer.MIN_VALUE;
        private final String[] headerLabels = new String[] {"0", "0", "0", "0", "0", "0"};
        private final ItemStack[][] previewStacks = new ItemStack[1 + PREVIEW_ITEMS.length][PREVIEW_COLUMN_COUNT];
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
    }
}
