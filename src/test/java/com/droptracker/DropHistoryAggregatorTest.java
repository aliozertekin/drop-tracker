package com.droptracker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class DropHistoryAggregatorTest
{
    @Test
    public void combinesSameItemAcrossDifferentDaysAndKeepsLatestDropTime()
    {
        long first = Instant.parse("2026-01-01T10:00:00Z").toEpochMilli();
        long second = Instant.parse("2026-02-12T22:30:00Z").toEpochMilli();
        DropRecord one = new DropRecord(1511, "Logs", 3, 100, 60, first);
        DropRecord two = new DropRecord(1511, "Logs", 4, 110, 60, second);

        List<DropRecord> result = DropHistoryAggregator.aggregateByItem(Arrays.asList(one, two));

        Assert.assertEquals(1, result.size());
        DropRecord combined = result.get(0);
        Assert.assertEquals(7L, combined.getQuantity());
        Assert.assertEquals(110L, combined.getUnitPrice());
        Assert.assertEquals(60L, combined.getHighAlchPrice());
        Assert.assertEquals(740L, combined.getTotalValue());
        Assert.assertEquals(420L, combined.getTotalHighAlchValue());
        Assert.assertEquals(second, combined.getTimestamp());
    }

    @Test
    public void differentItemsRemainSeparate()
    {
        long timestamp = Instant.parse("2026-06-10T10:00:00Z").toEpochMilli();
        List<DropRecord> result = DropHistoryAggregator.aggregateByItem(Arrays.asList(
            new DropRecord(1511, "Logs", 3, 100, 60, timestamp),
            new DropRecord(1513, "Oak logs", 2, 200, 120, timestamp)));

        Assert.assertEquals(2, result.size());
    }

    @Test
    public void addOrMergeUpdatesExistingItemRegardlessOfDate()
    {
        long firstDay = Instant.parse("2026-04-12T10:00:00Z").toEpochMilli();
        long nextDay = Instant.parse("2026-04-13T10:00:00Z").toEpochMilli();
        List<DropRecord> mutable = new ArrayList<>(Arrays.asList(
            new DropRecord(1511, "Logs", 3, 100, 60, firstDay)));

        DropHistoryAggregator.addOrMerge(mutable,
            new DropRecord(1511, "Logs", 5, 90, 60, nextDay));

        Assert.assertEquals(1, mutable.size());
        Assert.assertEquals(8L, mutable.get(0).getQuantity());
        Assert.assertEquals(750L, mutable.get(0).getTotalValue());
        Assert.assertEquals(nextDay, mutable.get(0).getTimestamp());
    }

    @Test
    public void latestTimestampWinsEvenIfLegacyRecordsAreOutOfOrder()
    {
        long older = Instant.parse("2026-01-01T10:00:00Z").toEpochMilli();
        long newer = Instant.parse("2026-05-01T10:00:00Z").toEpochMilli();
        List<DropRecord> result = DropHistoryAggregator.aggregateByItem(Arrays.asList(
            new DropRecord(1511, "Old logs name", 2, 90, 60, newer),
            new DropRecord(1511, "Logs", 3, 100, 60, older)));

        Assert.assertEquals(1, result.size());
        Assert.assertEquals(5L, result.get(0).getQuantity());
        Assert.assertEquals(90L, result.get(0).getUnitPrice());
        Assert.assertEquals("Old logs name", result.get(0).getItemName());
        Assert.assertEquals(newer, result.get(0).getTimestamp());
    }
}
