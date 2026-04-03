package org.example2.solips;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.EnchantmentLevelEntry;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;
import org.example2.solips.mixin.client.BossBarHudAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    private static final int TARGET_BOOKSHELVES = 0;
    private static final int TARGET_ICON_SIZE = 16;
    private static final int TARGET_ICON_GAP = 2;
    private static final int TARGET_ROW_LABEL_GAP = 4;
    private static final int TARGET_ROW_LABEL_COLOR = 0xFFFFFF;
    private static final int LIST_OFFSET_Y = 6;
    private static final int TARGET_ROW_GAP = 0;
    private static final int TOP_ROW_NUMBER = 1;
    private static final int BOTTOM_ROW_NUMBER = 2;
    private static final int BOSS_BAR_TOP_MARGIN = 12;
    private static final int BOSS_BAR_VERTICAL_SPACING = 19;
    private static final int BOSS_BAR_SCREEN_DIVISOR = 3;
    private static final int LOW_COST_LIMIT = 4;

    private static final int CELL_WIDTH = 22;
    private static final int CELL_HEIGHT = 20;
    private static final int GRID_MARGIN = 4;
    private static final int PREVIEW_COLUMN_COUNT = 6;
    private static final int GRID_COLUMNS = 1 + PREVIEW_COLUMN_COUNT;
    private static final int GRID_WIDTH = GRID_COLUMNS * CELL_WIDTH;

    private static final Identifier FIRE_ASPECT_ID = Identifier.ofVanilla("fire_aspect");
    private static final Identifier DEPTH_STRIDER_ID = Identifier.ofVanilla("depth_strider");
    private static final Identifier FORTUNE_ID = Identifier.ofVanilla("fortune");
    private static final Identifier EFFICIENCY_ID = Identifier.ofVanilla("efficiency");
    private static final Identifier SHARPNESS_ID = Identifier.ofVanilla("sharpness");
    private static final Identifier PROTECTION_ID = Identifier.ofVanilla("protection");

    private static final TargetItem[] TARGET_ITEMS = new TargetItem[] {
            new TargetItem(
                    Items.IRON_SWORD,
                    "Iron Sword",
                    new TargetRule[] {
                            new TargetRule("Fire", FIRE_ASPECT_ID, 0, false),
                            new TargetRule("Sharp II", SHARPNESS_ID, 2, true)
                    }
            ),
            new TargetItem(
                    Items.IRON_BOOTS,
                    "Iron Boots",
                    new TargetRule[] {
                            new TargetRule("Depth", DEPTH_STRIDER_ID, 0, false),
                            new TargetRule("Prot II", PROTECTION_ID, 2, true)
                    }
            ),
            new TargetItem(
                    Items.IRON_HELMET,
                    "Iron Helmet",
                    new TargetRule[] {new TargetRule("Prot II", PROTECTION_ID, 2, true)}
            ),
            new TargetItem(
                    Items.IRON_CHESTPLATE,
                    "Iron Chestplate",
                    new TargetRule[] {new TargetRule("Prot II", PROTECTION_ID, 2, true)}
            ),
            new TargetItem(
                    Items.IRON_LEGGINGS,
                    "Iron Leggings",
                    new TargetRule[] {new TargetRule("Prot II", PROTECTION_ID, 2, true)}
            ),
            new TargetItem(
                    Items.GOLDEN_PICKAXE,
                    "Gold Pick",
                    new TargetRule[] {
                            new TargetRule("Fortune", FORTUNE_ID, 0, false),
                            new TargetRule("Eff II", EFFICIENCY_ID, 2, true)
                    }
            ),
            new TargetItem(
                    Items.GOLDEN_SWORD,
                    "Gold Sword",
                    new TargetRule[] {
                            new TargetRule("Sharp II", SHARPNESS_ID, 2, true),
                            new TargetRule("Fire", FIRE_ASPECT_ID, 0, false)
                    }
            )
    };

    private static final TargetItem[] LOWER_ROW_ITEMS = new TargetItem[] {
            new TargetItem(
                    Items.IRON_SWORD,
                    "Iron Sword",
                    new TargetRule[] {new TargetRule("Sharp I", SHARPNESS_ID, 1, true)}
            ),
            new TargetItem(
                    Items.IRON_PICKAXE,
                    "Iron Pick",
                    new TargetRule[] {new TargetRule("Eff I", EFFICIENCY_ID, 1, true)}
            ),
            new TargetItem(
                    Items.IRON_HELMET,
                    "Iron Helmet",
                    new TargetRule[] {new TargetRule("Prot I", PROTECTION_ID, 1, true)}
            ),
            new TargetItem(
                    Items.IRON_CHESTPLATE,
                    "Iron Chestplate",
                    new TargetRule[] {new TargetRule("Prot I", PROTECTION_ID, 1, true)}
            ),
            new TargetItem(
                    Items.IRON_LEGGINGS,
                    "Iron Leggings",
                    new TargetRule[] {new TargetRule("Prot I", PROTECTION_ID, 1, true)}
            ),
            new TargetItem(
                    Items.IRON_BOOTS,
                    "Iron Boots",
                    new TargetRule[] {new TargetRule("Prot I", PROTECTION_ID, 1, true)}
            )
    };

    private static final GridCache SOLVED_GRID = new GridCache();
    private static final TargetCache TARGET_CACHE = new TargetCache();
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
        clearTargetCache(TARGET_CACHE);
        HudRenderCallback.EVENT.register(MultiItemPreviewOverlay::onHudRender);
    }

    private static void onHudRender(DrawContext drawContext, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || !ClientFeatureToggle.isEnabled() || !ClientFeatureToggle.isHudVisible()) {
            return;
        }

        renderLeftHud(client, drawContext, client.textRenderer);

        if (SeedCrackState.isSolved()) {
            renderBossBarTargets(client, drawContext, client.textRenderer);
            renderRightTopGrid(client, drawContext, client.textRenderer);
        }
    }

    private static void renderLeftHud(MinecraftClient client, DrawContext drawContext, TextRenderer font) {
        int left = 5;
        int y = 5;
        String shotbowHost = ShotbowServerUtil.resolveShotbowHost(client);

        drawContext.drawText(font, "hudKey=M", left, y, 0xAAAAAA, false);
        y += font.fontHeight + 2;
        drawContext.drawText(font, "toggleKey=N", left, y, 0xAAAAAA, false);
        y += font.fontHeight + 2;
        drawContext.drawText(
                font,
                "autoKey=H " + (ClientFeatureToggle.isAutoInsertEnabled() ? "on" : "off"),
                left,
                y,
                ClientFeatureToggle.isAutoInsertEnabled() ? 0x55FF55 : 0xAAAAAA,
                false
        );
        y += font.fontHeight + 2;
        drawContext.drawText(
                font,
                shotbowHost == null ? "shotbow=no" : "shotbow=" + shotbowHost,
                left,
                y,
                shotbowHost == null ? 0xAAAAAA : 0x55FF55,
                false
        );
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

    private static void renderBossBarTargets(MinecraftClient client, DrawContext drawContext, TextRenderer font) {
        int solvedSeed = SeedCrackState.isSolved() ? SeedCrackState.getSolvedSeed() : Integer.MIN_VALUE;
        if (solvedSeed == Integer.MIN_VALUE) {
            return;
        }

        rebuildTargetCacheIfNeeded(client, TARGET_CACHE, solvedSeed);
        if (TARGET_CACHE.previewStacks.isEmpty() && TARGET_CACHE.lowCostProtectionStacks.isEmpty()) {
            return;
        }

        int y = getBossBarBottom(client) + LIST_OFFSET_Y;
        int bottomRowY = y + TARGET_ICON_SIZE + TARGET_ROW_GAP;
        if (!TARGET_CACHE.previewStacks.isEmpty()) {
            renderTargetRow(client, drawContext, font, y, TARGET_CACHE.previewStacks, TOP_ROW_NUMBER);
        }
        if (!TARGET_CACHE.lowCostProtectionStacks.isEmpty()) {
            renderTargetRow(client, drawContext, font, bottomRowY, TARGET_CACHE.lowCostProtectionStacks, BOTTOM_ROW_NUMBER);
        }
    }

    private static void renderTargetRow(
            MinecraftClient client,
            DrawContext drawContext,
            TextRenderer font,
            int y,
            List<ItemStack> stacks,
            int rowNumber
    ) {
        int screenWidth = client.getWindow().getScaledWidth();
        int totalWidth = (stacks.size() * TARGET_ICON_SIZE) + ((stacks.size() - 1) * TARGET_ICON_GAP);
        int x = (screenWidth - totalWidth) / 2;
        String label = Integer.toString(rowNumber);
        int labelX = Math.max(2, x - TARGET_ROW_LABEL_GAP - font.getWidth(label));
        int labelY = y + ((TARGET_ICON_SIZE - font.fontHeight) / 2);
        drawContext.drawText(font, label, labelX, labelY, TARGET_ROW_LABEL_COLOR, false);
        for (ItemStack stack : stacks) {
            drawContext.drawItem(stack, x, y);
            x += TARGET_ICON_SIZE + TARGET_ICON_GAP;
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

    private static int getBossBarBottom(MinecraftClient client) {
        int y = BOSS_BAR_TOP_MARGIN;
        int limit = client.getWindow().getScaledHeight() / BOSS_BAR_SCREEN_DIVISOR;
        int renderedBossBars = getRenderedBossBarCount(client);
        for (int i = 0; i < renderedBossBars && y < limit; i++) {
            y += BOSS_BAR_VERTICAL_SPACING;
        }
        return y;
    }

    private static int getRenderedBossBarCount(MinecraftClient client) {
        BossBarHud bossBarHud = client.inGameHud == null ? null : client.inGameHud.getBossBarHud();
        if (!(bossBarHud instanceof BossBarHudAccessor accessor)) {
            return 0;
        }
        Map<UUID, ?> bossBars = accessor.solips$getBossBars();
        return bossBars == null ? 0 : bossBars.size();
    }

    private static void rebuildTargetCacheIfNeeded(MinecraftClient client, TargetCache cache, int seed) {
        if (seed == Integer.MIN_VALUE) {
            clearTargetCache(cache);
            return;
        }
        if (cache.cachedSeed == seed) {
            return;
        }

        DynamicRegistryManager registryManager = EnchantmentScreenHandlerUtil.getRegistryManager(client);
        if (registryManager == null) {
            clearTargetCache(cache);
            return;
        }
        Registry<Enchantment> enchantmentRegistry = registryManager.get(RegistryKeys.ENCHANTMENT);
        if (enchantmentRegistry == null) {
            clearTargetCache(cache);
            return;
        }

        clearTargetCache(cache);
        cache.cachedSeed = seed;
        for (TargetItem targetItem : TARGET_ITEMS) {
            collectMatchesForItem(client, seed, targetItem, enchantmentRegistry, cache.previewStacks, Integer.MAX_VALUE);
        }
        for (TargetItem targetItem : LOWER_ROW_ITEMS) {
            collectMatchesForItem(client, seed, targetItem, enchantmentRegistry, cache.lowCostProtectionStacks, LOW_COST_LIMIT);
        }
    }

    private static void clearTargetCache(TargetCache cache) {
        cache.cachedSeed = Integer.MIN_VALUE;
        cache.previewStacks.clear();
        cache.lowCostProtectionStacks.clear();
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

    private static void collectMatchesForItem(
            MinecraftClient client,
            int seed,
            TargetItem targetItem,
            Registry<Enchantment> enchantmentRegistry,
            List<ItemStack> output,
            int maxCost
    ) {
        collectConfiguredMatchesForItem(
                client,
                seed,
                targetItem.item(),
                TARGET_BOOKSHELVES,
                maxCost,
                enchantmentRegistry,
                targetItem.rules(),
                false,
                output
        );
    }

    private static void collectConfiguredMatchesForItem(
            MinecraftClient client,
            int seed,
            Item item,
            int bookshelves,
            int maxCost,
            Registry<Enchantment> enchantmentRegistry,
            TargetRule[] rules,
            boolean requireAllRules,
            List<ItemStack> output
    ) {
        ItemStack inputStack = new ItemStack(item);
        int[] costs = calculateSlotCosts(seed, bookshelves, inputStack);
        for (int slot = 0; slot < costs.length; slot++) {
            int cost = costs[slot];
            if (cost <= 0 || cost > maxCost) {
                continue;
            }

            List<EnchantmentLevelEntry> entries = generateEnchantments(client, seed, inputStack, slot, cost);
            if (entries == null || entries.isEmpty()) {
                continue;
            }

            if (!matchesRules(entries, enchantmentRegistry, rules, requireAllRules)) {
                continue;
            }

            output.add(buildDisplayStack(item, entries));
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

    private static boolean matchesRules(
            List<EnchantmentLevelEntry> entries,
            Registry<Enchantment> enchantmentRegistry,
            TargetRule[] rules,
            boolean requireAllRules
    ) {
        if (requireAllRules) {
            for (TargetRule rule : rules) {
                if (!matchesRule(entries, enchantmentRegistry, rule)) {
                    return false;
                }
            }
            return rules.length > 0;
        }
        for (TargetRule rule : rules) {
            if (matchesRule(entries, enchantmentRegistry, rule)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesRule(
            List<EnchantmentLevelEntry> entries,
            Registry<Enchantment> enchantmentRegistry,
            TargetRule rule
    ) {
        for (EnchantmentLevelEntry entry : entries) {
            Identifier id = enchantmentRegistry.getId(entry.enchantment.value());
            if (id == null || !id.equals(rule.enchantmentId())) {
                continue;
            }
            if (rule.exactLevel()) {
                if (entry.level == rule.requiredLevel()) {
                    return true;
                }
                continue;
            }
            if (entry.level >= rule.requiredLevel()) {
                return true;
            }
        }
        return false;
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

    private static ItemStack buildDisplayStack(Item item, List<EnchantmentLevelEntry> entries) {
        ItemStack displayStack = new ItemStack(item);
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

    private static final class TargetCache {
        private int cachedSeed = Integer.MIN_VALUE;
        private final List<ItemStack> previewStacks = new ArrayList<>();
        private final List<ItemStack> lowCostProtectionStacks = new ArrayList<>();
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

    private record TargetItem(Item item, String label, TargetRule[] rules) {
    }

    private record TargetRule(String label, Identifier enchantmentId, int requiredLevel, boolean exactLevel) {
    }
}
