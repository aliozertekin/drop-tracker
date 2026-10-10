package com.droptracker;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class DropConfirmationTrackerTest
{
    @Test
    public void recordsOnlyAfterInventoryQuantityFalls()
    {
        DropConfirmationTracker tracker = new DropConfirmationTracker();
        Map<Integer, Integer> before = counts(995, 250);
        tracker.queue(995, "Coins", 250, 1, 1000L, before);

        Assert.assertTrue(tracker.inventoryChanged(before).isEmpty());

        List<DropRecord> confirmed = tracker.inventoryChanged(Collections.<Integer, Integer>emptyMap());
        Assert.assertEquals(1, confirmed.size());
        Assert.assertEquals(250, confirmed.get(0).getQuantity());
        Assert.assertEquals(250L, confirmed.get(0).getTotalValue());
    }

    @Test
    public void recordsOnlyTheActualQuantityLost()
    {
        DropConfirmationTracker tracker = new DropConfirmationTracker();
        tracker.queue(995, "Coins", 500, 1, 1000L, counts(995, 500));

        List<DropRecord> confirmed = tracker.inventoryChanged(counts(995, 125));
        Assert.assertEquals(1, confirmed.size());
        Assert.assertEquals(375, confirmed.get(0).getQuantity());
    }

    @Test
    public void unrelatedInventoryChangesDoNotConfirmADrop()
    {
        DropConfirmationTracker tracker = new DropConfirmationTracker();
        Map<Integer, Integer> before = counts(995, 500);
        tracker.queue(995, "Coins", 500, 1, 1000L, before);

        Assert.assertTrue(tracker.inventoryChanged(counts(995, 500, 1511, 2)).isEmpty());
    }

    @Test
    public void pendingDropExpiresIfInventoryDoesNotChange()
    {
        DropConfirmationTracker tracker = new DropConfirmationTracker();
        tracker.queue(995, "Coins", 500, 1, 1000L, counts(995, 500));
        for (int i = 0; i < 6; i++)
        {
            tracker.gameTick();
        }
        Assert.assertTrue(tracker.inventoryChanged(Collections.<Integer, Integer>emptyMap()).isEmpty());
    }

    @Test
    public void handlesMultipleDistinctDropsInOneInventoryUpdate()
    {
        DropConfirmationTracker tracker = new DropConfirmationTracker();
        Map<Integer, Integer> before = counts(995, 500, 1511, 12);
        tracker.queue(995, "Coins", 500, 1, 1000L, before);
        tracker.queue(1511, "Logs", 12, 100, 1100L, before);

        List<DropRecord> confirmed = tracker.inventoryChanged(Collections.<Integer, Integer>emptyMap());
        Assert.assertEquals(2, confirmed.size());
        Assert.assertEquals(500L, confirmed.get(0).getTotalValue());
        Assert.assertEquals(1200L, confirmed.get(1).getTotalValue());
    }

    @Test
    public void multipleDropsOfSameItemDoNotDoubleCountTheSameInventoryLoss()
    {
        DropConfirmationTracker tracker = new DropConfirmationTracker();
        Map<Integer, Integer> before = counts(995, 500);
        tracker.queue(995, "Coins", 500, 1, 1000L, before);
        tracker.queue(995, "Coins", 500, 1, 1100L, before);

        List<DropRecord> confirmed = tracker.inventoryChanged(Collections.<Integer, Integer>emptyMap());
        Assert.assertEquals(1, confirmed.size());
        Assert.assertEquals(500, confirmed.get(0).getQuantity());
    }

    @Test
    public void carriesHighAlchPriceIntoConfirmedDrop()
    {
        DropConfirmationTracker tracker = new DropConfirmationTracker();
        tracker.queue(1511, "Logs", 5, 100, 60, 1000L, counts(1511, 5));

        List<DropRecord> confirmed = tracker.inventoryChanged(Collections.<Integer, Integer>emptyMap());

        Assert.assertEquals(1, confirmed.size());
        Assert.assertEquals(500L, confirmed.get(0).getTotalValue());
        Assert.assertEquals(300L, confirmed.get(0).getTotalHighAlchValue());
    }

    private static Map<Integer, Integer> counts(int... idQuantityPairs)
    {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int i = 0; i + 1 < idQuantityPairs.length; i += 2)
        {
            counts.put(idQuantityPairs[i], idQuantityPairs[i + 1]);
        }
        return counts;
    }
}
