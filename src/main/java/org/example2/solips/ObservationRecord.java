package org.example2.solips;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.Arrays;

public final class ObservationRecord {
    private final Item item;
    private final int bookshelves;
    private final int[] costs;
    private final int[] clueIds;
    private final int[] clueLevels;
    private final String key;
    private final String costKey;

    public ObservationRecord(Item item, int bookshelves, int[] costs, int[] clueIds, int[] clueLevels) {
        this.item = item;
        this.bookshelves = bookshelves;
        this.costs = Arrays.copyOf(costs, 3);
        this.clueIds = Arrays.copyOf(clueIds, 3);
        this.clueLevels = Arrays.copyOf(clueLevels, 3);
        normalizeIgnoredClues(this.item, this.costs, this.clueIds, this.clueLevels);
        this.key = buildKey(this.item, this.bookshelves, this.costs, this.clueIds, this.clueLevels);
        this.costKey = buildCostKey(bookshelves, costs);
    }

    public Item getItem() {
        return item;
    }

    public ItemStack createStack() {
        return new ItemStack(item);
    }

    public int getBookshelves() {
        return bookshelves;
    }

    public int[] getCosts() {
        return Arrays.copyOf(costs, 3);
    }

    public int[] getClueIds() {
        return Arrays.copyOf(clueIds, 3);
    }

    public int[] getClueLevels() {
        return Arrays.copyOf(clueLevels, 3);
    }

    public String getKey() {
        return key;
    }

    public String getCostKey() {
        return costKey;
    }

    public static String buildKey(Item item, int bookshelves, int[] costs, int[] clueIds, int[] clueLevels) {
        int[] normalizedClueIds = Arrays.copyOf(clueIds, 3);
        int[] normalizedClueLevels = Arrays.copyOf(clueLevels, 3);
        normalizeIgnoredClues(item, costs, normalizedClueIds, normalizedClueLevels);
        return item + "|" + bookshelves + "|"
                + costs[0] + "," + costs[1] + "," + costs[2] + "|"
                + normalizedClueIds[0] + "," + normalizedClueIds[1] + "," + normalizedClueIds[2] + "|"
                + normalizedClueLevels[0] + "," + normalizedClueLevels[1] + "," + normalizedClueLevels[2];
    }

    public static String buildCostKey(int bookshelves, int[] costs) {
        return bookshelves + "|" + costs[0] + "," + costs[1] + "," + costs[2];
    }

    public static int[] buildRawCostMinimums(int bookshelves, int[] costs) {
        int[] mins = new int[3];
        int[] maxs = new int[3];
        fillRawCostBounds(bookshelves, costs, mins, maxs);
        return mins;
    }

    public static int[] buildRawCostMaximums(int bookshelves, int[] costs) {
        int[] mins = new int[3];
        int[] maxs = new int[3];
        fillRawCostBounds(bookshelves, costs, mins, maxs);
        return maxs;
    }

    private static void fillRawCostBounds(int bookshelves, int[] costs, int[] mins, int[] maxs) {
        for (int slot = 0; slot < 3; slot++) {
            int observed = costs != null && slot < costs.length ? costs[slot] : 0;
            if (observed > 0) {
                mins[slot] = observed;
                maxs[slot] = observed;
                continue;
            }

            // Hidden slots still carry a raw cost below the display threshold.
            mins[slot] = 0;
            maxs[slot] = slot;
        }

        if (bookshelves == 0 && costs != null && costs.length >= 2 && costs[1] == 0) {
            mins[1] = 1;
            maxs[1] = 1;
        }

        if (costs != null && costs.length >= 3 && costs[2] == 0) {
            if (bookshelves == 0) {
                mins[2] = 1;
                maxs[2] = 2;
            } else if (bookshelves == 1) {
                mins[2] = 2;
                maxs[2] = 2;
            }
        }
    }

    private static void normalizeIgnoredClues(Item item, int[] costs, int[] clueIds, int[] clueLevels) {
        if (item != Items.FISHING_ROD) {
            return;
        }
        for (int slot = 0; slot < 3; slot++) {
            if (costs[slot] <= 9) {
                clueIds[slot] = -1;
                clueLevels[slot] = 0;
            }
        }
    }
}
