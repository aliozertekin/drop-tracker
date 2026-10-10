package com.droptracker;

import org.junit.Assert;
import org.junit.Test;

public class DropRecordTest
{
    @Test
    public void calculatesTotalValueUsingLongArithmetic()
    {
        DropRecord record = new DropRecord(995, "Coins", 2_000_000_000, Integer.MAX_VALUE, 123L);
        Assert.assertEquals(4_294_967_294_000_000_000L, record.getTotalValue());
    }

    @Test
    public void calculatesHighAlchTotal()
    {
        DropRecord record = new DropRecord(1511, "Logs", 12, 100, 60, 123L);
        Assert.assertEquals(1200L, record.getTotalValue());
        Assert.assertEquals(720L, record.getTotalHighAlchValue());
    }

    @Test
    public void mergePreservesCapturedTotalsWhenUnitPricesChange()
    {
        DropRecord first = new DropRecord(1511, "Logs", 3, 100, 60, 100L);
        DropRecord second = new DropRecord(1511, "Logs", 4, 110, 60, 200L);
        DropRecord merged = first.merge(second);

        Assert.assertEquals(7L, merged.getQuantity());
        Assert.assertEquals(110L, merged.getUnitPrice());
        Assert.assertEquals(740L, merged.getTotalValue());
        Assert.assertEquals(420L, merged.getTotalHighAlchValue());
        Assert.assertEquals(200L, merged.getTimestamp());
    }

    @Test
    public void refreshesLegacyHighAlchValueWithoutChangingGeTotal()
    {
        DropRecord legacy = new DropRecord(1511, "Logs", 5, 100, 123L);
        DropRecord refreshed = legacy.withHighAlchPrice(60);

        Assert.assertEquals(500L, refreshed.getTotalValue());
        Assert.assertEquals(300L, refreshed.getTotalHighAlchValue());
    }

    @Test
    public void rejectsInvalidQuantity()
    {
        try
        {
            new DropRecord(995, "Coins", 0, 1, 123L);
            Assert.fail("Expected invalid quantity to be rejected");
        }
        catch (IllegalArgumentException expected)
        {
            // Expected.
        }
    }

    @Test
    public void recognizesValidRecords()
    {
        Assert.assertTrue(new DropRecord(995, "Coins", 100, 1, 123L).isValid());
    }
}
