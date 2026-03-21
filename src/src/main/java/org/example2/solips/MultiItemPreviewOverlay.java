package org.example2.solips;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.random.Random;

public final class MultiItemPreviewOverlay {
    private static final Item[] PREVIEW_ITEMS = new Item[] {
            Items.DIAMOND_PICKAXE,
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
            Items.BOOK
    };

    private static final int CELL_WIDTH = 22;
    private static final int CELL_HEIGHT = 20;
    private static final int GRID_MARGIN = 4;
    private static final int GRID_COLUMNS = 7;
    private static final int GRID_ROWS = 1 + PREVIEW_ITEMS.length;
    private static final int GRID_WIDTH = GRID_COLUMNS * CELL_WIDTH;
    private static final int GRID_HEIGHT = GRID_ROWS * CELL_HEIGHT;

    private static final String[] headerLabels = new String[] {"0", "0", "0", "0", "0", "0"};
    private static int cachedHeaderSeed = Integer.MIN_VALUE;
    private static boolean initialized = false;

    private MultiItemPreviewOverlay() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        HudRenderCallback.EVENT.register(MultiItemPreviewOverlay::onHudRender);
    }

    private static void onHudRender(DrawContext drawContext, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || !ClientFeatureToggle.isEnabled()) {
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

        drawContext.drawText(font, "resetKey=M", left, y, 0xAAAAAA, false);
        y += font.fontHeight + 2;
        drawContext.drawText(font, "toggleKey=N", left, y, 0xAAAAAA, false);
        y += font.fontHeight + 2;
        drawContext.drawText(font, "observations=" + SeedCrackState.getObservationCount(), left, y, 0xFFFFFF, false);
        y += font.fontHeight + 2;
        drawContext.drawText(font, "applied=" + SeedCrackState.getAppliedObservationCount(), left, y, 0xFFFFFF, false);
        y += font.fontHeight + 2;
        drawContext.drawText(font, "queued=" + SeedCrackState.getQueuedObservationCount(), left, y, 0xFFFFFF, false);
        y += font.fontHeight + 2;
        drawContext.drawText(font, "running=" + SeedCrackState.isRunning(), left, y, 0xFFFFFF, false);
        y += font.fontHeight + 2;
        drawContext.drawText(font, "elapsed=" + SeedCrackState.getElapsedFormatted(), left, y,
                SeedCrackState.isSolved() ? 0x55FF55 : (SeedCrackState.isStopwatchRunning() ? 0xFFFF55 : 0xCCCCCC), false);
        y += font.fontHeight + 2;
        drawContext.drawText(font, "checked=" + SeedCrackState.getChecked(), left, y, 0xFFFFFF, false);
        y += font.fontHeight + 2;
        drawContext.drawText(font, "matched=" + SeedCrackState.getMatched(), left, y, 0xFFFFFF, false);
        y += font.fontHeight + 2;
        drawContext.drawText(font, "solved=" + SeedCrackState.isSolved(), left, y, 0xFFFFFF, false);
        y += font.fontHeight + 2;

        if (SeedCrackState.isSolved()) {
            drawContext.drawText(font, "solvedSeed=" + Integer.toUnsignedString(SeedCrackState.getSolvedSeed()), left, y, 0x55FF55, false);
            y += font.fontHeight + 2;
        }

        ObservationRecord snapshot = ObservedEnchantState.snapshot();
        if (snapshot != null) {
            drawContext.drawText(font, "bookshelves=" + snapshot.getBookshelves() + " (auto)", left, y, 0xAAAAFF, false);
            y += font.fontHeight + 2;
            drawContext.drawText(font, "latest=" + snapshot.getKey(), left, y, 0xAAAAFF, false);
        } else {
            drawContext.drawText(font, "bookshelves=(none)", left, y, 0x888888, false);
            y += font.fontHeight + 2;
            drawContext.drawText(font, "latest=(none)", left, y, 0x888888, false);
        }
    }

    private static void renderRightTopGrid(MinecraftClient client, DrawContext drawContext, TextRenderer font) {
        int startX = client.getWindow().getScaledWidth() - GRID_WIDTH - GRID_MARGIN;
        int startY = GRID_MARGIN;

        updateHeaderLabels();

        drawCentered(drawContext, font, "", startX, startY, CELL_WIDTH, CELL_HEIGHT, 0xFFFFFF);
        for (int col = 0; col < 6; col++) {
            drawCentered(drawContext, font, headerLabels[col], startX + CELL_WIDTH * (col + 1), startY, CELL_WIDTH, CELL_HEIGHT, 0xFFFFFF);
        }

        for (int row = 0; row < PREVIEW_ITEMS.length; row++) {
            int y = startY + (row + 1) * CELL_HEIGHT;
            drawContext.drawItem(new ItemStack(PREVIEW_ITEMS[row]), startX + 3, y + 2);
            for (int col = 0; col < 6; col++) {
                int x = startX + (col + 1) * CELL_WIDTH;
                drawContext.drawItem(new ItemStack(PREVIEW_ITEMS[row]), x + 3, y + 2);
            }
        }
    }

    private static void updateHeaderLabels() {
        int solvedSeed = SeedCrackState.getSolvedSeed();
        if (cachedHeaderSeed == solvedSeed) {
            return;
        }

        cachedHeaderSeed = solvedSeed;
        fillHeaderCosts(solvedSeed, 0, 0);
        fillHeaderCosts(solvedSeed, 15, 3);
    }

    private static void fillHeaderCosts(int seed, int bookshelves, int offset) {
        ItemStack stack = new ItemStack(Items.IRON_PICKAXE);
        Random random = Random.create(seed);

        for (int slot = 0; slot < 3; slot++) {
            int cost = EnchantmentHelper.calculateRequiredExperienceLevel(random, slot, bookshelves, stack);
            if (cost < slot + 1) {
                cost = 0;
            }
            headerLabels[offset + slot] = Integer.toString(cost);
        }
    }

    private static void drawCentered(DrawContext drawContext, TextRenderer font, String text, int x, int y, int width, int height, int color) {
        int drawX = x + (width - font.getWidth(text)) / 2;
        int drawY = y + (height - font.fontHeight) / 2;
        drawContext.drawText(font, text, drawX, drawY, color, false);
    }
}
