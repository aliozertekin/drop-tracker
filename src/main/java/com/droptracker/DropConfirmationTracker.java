package com.droptracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Correlates an explicit inventory "Drop" click with the subsequent decrease in
 * inventory quantity. This avoids counting a click that did not actually remove the item.
 * Package-private so the state machine can be unit tested without a RuneLite client.
 */
final class DropConfirmationTracker
{
    private static final int PENDING_TIMEOUT_TICKS = 5;

    private final List<PendingDrop> pending = new ArrayList<>();
    private Map<Integer, Integer> previousCounts = Collections.emptyMap();
    private int gameTick;

    void queue(int itemId, String itemName, int expectedQuantity, long unitPrice, long timestamp,
        Map<Integer, Integer> currentCounts)
    {
        queue(itemId, itemName, expectedQuantity, unitPrice, 0L, timestamp, currentCounts);
    }

    void queue(int itemId, String itemName, int expectedQuantity, long unitPrice, long highAlchPrice,
        long timestamp, Map<Integer, Integer> currentCounts)
    {
        if (itemId <= 0 || expectedQuantity <= 0)
        {
            return;
        }
        if (pending.isEmpty())
        {
            previousCounts = normalizedCopy(currentCounts);
        }
        pending.add(new PendingDrop(itemId, itemName, expectedQuantity, unitPrice, highAlchPrice, timestamp,
            gameTick + PENDING_TIMEOUT_TICKS));
    }

    List<DropRecord> inventoryChanged(Map<Integer, Integer> currentCounts)
    {
        Map<Integer, Integer> current = normalizedCopy(currentCounts);
        Map<Integer, Integer> losses = new HashMap<>();
        for (Map.Entry<Integer, Integer> before : previousCounts.entrySet())
        {
            int after = current.getOrDefault(before.getKey(), 0);
            if (before.getValue() > after)
            {
                losses.put(before.getKey(), before.getValue() - after);
            }
        }
        previousCounts = current;
        expireOldPending();
        if (pending.isEmpty() || losses.isEmpty())
        {
            return Collections.emptyList();
        }

        List<DropRecord> confirmed = new ArrayList<>();
        for (Map.Entry<Integer, Integer> loss : losses.entrySet())
        {
            int remainingLoss = loss.getValue();
            Iterator<PendingDrop> iterator = pending.iterator();
            while (iterator.hasNext() && remainingLoss > 0)
            {
                PendingDrop candidate = iterator.next();
                if (candidate.itemId != loss.getKey())
                {
                    continue;
                }
                int confirmedQuantity = Math.min(candidate.expectedQuantity, remainingLoss);
                if (confirmedQuantity > 0)
                {
                    confirmed.add(new DropRecord(candidate.itemId, candidate.itemName, confirmedQuantity,
                        candidate.unitPrice, candidate.highAlchPrice, candidate.timestamp));
                    remainingLoss -= confirmedQuantity;
                }
                iterator.remove();
            }
        }
        confirmed.sort(Comparator.comparingLong(DropRecord::getTimestamp));
        return confirmed;
    }

    void gameTick()
    {
        gameTick++;
        expireOldPending();
    }

    void reset(Map<Integer, Integer> currentCounts)
    {
        pending.clear();
        previousCounts = normalizedCopy(currentCounts);
    }

    private void expireOldPending()
    {
        pending.removeIf(drop -> gameTick > drop.expiresAfterTick);
    }

    private static Map<Integer, Integer> normalizedCopy(Map<Integer, Integer> input)
    {
        Map<Integer, Integer> result = new HashMap<>();
        if (input != null)
        {
            for (Map.Entry<Integer, Integer> entry : input.entrySet())
            {
                Integer id = entry.getKey();
                Integer quantity = entry.getValue();
                if (id != null && id > 0 && quantity != null && quantity > 0)
                {
                    result.put(id, quantity);
                }
            }
        }
        return result;
    }

    private static final class PendingDrop
    {
        private final int itemId;
        private final String itemName;
        private final int expectedQuantity;
        private final long unitPrice;
        private final long highAlchPrice;
        private final long timestamp;
        private final int expiresAfterTick;

        private PendingDrop(int itemId, String itemName, int expectedQuantity, long unitPrice,
            long highAlchPrice, long timestamp, int expiresAfterTick)
        {
            this.itemId = itemId;
            this.itemName = itemName;
            this.expectedQuantity = expectedQuantity;
            this.unitPrice = Math.max(0L, unitPrice);
            this.highAlchPrice = Math.max(0L, highAlchPrice);
            this.timestamp = timestamp;
            this.expiresAfterTick = expiresAfterTick;
        }
    }
}
