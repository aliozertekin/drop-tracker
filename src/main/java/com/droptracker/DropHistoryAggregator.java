package com.droptracker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregates confirmed item drops into one all-time row per item ID. */
final class DropHistoryAggregator
{
    private DropHistoryAggregator()
    {
    }

    /**
     * Collapse imported or previously saved rows into one cumulative record per item.
     * DropRecord.merge preserves accumulated value totals and the latest drop timestamp.
     */
    static List<DropRecord> aggregateByItem(Collection<DropRecord> input)
    {
        Map<Integer, DropRecord> aggregated = new LinkedHashMap<>();
        if (input != null)
        {
            for (DropRecord record : input)
            {
                if (record == null || !record.isValid())
                {
                    continue;
                }
                DropRecord previous = aggregated.get(record.getItemId());
                aggregated.put(record.getItemId(), previous == null ? record : previous.merge(record));
            }
        }

        List<DropRecord> result = new ArrayList<>(aggregated.values());
        sortByLatestDrop(result);
        return result;
    }

    /** Add a newly confirmed drop to the item's lifetime aggregate, creating a row only if needed. */
    static void addOrMerge(List<DropRecord> history, DropRecord incoming)
    {
        if (incoming == null || !incoming.isValid())
        {
            return;
        }
        for (int i = 0; i < history.size(); i++)
        {
            DropRecord existing = history.get(i);
            if (existing.getItemId() == incoming.getItemId())
            {
                history.set(i, existing.merge(incoming));
                sortByLatestDrop(history);
                return;
            }
        }
        history.add(incoming);
        sortByLatestDrop(history);
    }

    private static void sortByLatestDrop(List<DropRecord> records)
    {
        records.sort(Comparator.comparingLong(DropRecord::getTimestamp)
            .thenComparingInt(DropRecord::getItemId));
    }
}
