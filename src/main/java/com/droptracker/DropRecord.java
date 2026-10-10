package com.droptracker;

import java.util.Objects;

/** Cumulative aggregate for one item deliberately dropped by the player; timestamp is the latest drop. */
public final class DropRecord
{
    private int itemId;
    private String itemName;
    private long quantity;
    private long unitPrice;
    private long highAlchPrice;
    private long timestamp;

    // Nullable so histories created by older versions can still calculate totals from unit price * quantity.
    private Long recordedGeTotal;
    private Long recordedHighAlchTotal;

    // Required by Gson.
    public DropRecord()
    {
    }

    /** Backward-compatible constructor for older tests and version-1 JSON records. */
    public DropRecord(int itemId, String itemName, long quantity, long unitPrice, long timestamp)
    {
        this(itemId, itemName, quantity, unitPrice, 0L, timestamp);
    }

    public DropRecord(int itemId, String itemName, long quantity, long unitPrice, long highAlchPrice, long timestamp)
    {
        this(itemId, itemName, quantity, unitPrice, highAlchPrice, timestamp,
            multiplySaturated(quantity, unitPrice), multiplySaturated(quantity, highAlchPrice));
    }

    private DropRecord(int itemId, String itemName, long quantity, long unitPrice, long highAlchPrice,
        long timestamp, long recordedGeTotal, long recordedHighAlchTotal)
    {
        if (itemId <= 0)
        {
            throw new IllegalArgumentException("itemId must be positive");
        }
        if (quantity <= 0)
        {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (unitPrice < 0)
        {
            throw new IllegalArgumentException("unitPrice cannot be negative");
        }
        if (highAlchPrice < 0)
        {
            throw new IllegalArgumentException("highAlchPrice cannot be negative");
        }
        if (timestamp < 0)
        {
            throw new IllegalArgumentException("timestamp cannot be negative");
        }
        if (recordedGeTotal < 0 || recordedHighAlchTotal < 0)
        {
            throw new IllegalArgumentException("recorded totals cannot be negative");
        }
        this.itemId = itemId;
        this.itemName = Objects.requireNonNull(itemName, "itemName");
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.highAlchPrice = highAlchPrice;
        this.timestamp = timestamp;
        this.recordedGeTotal = recordedGeTotal;
        this.recordedHighAlchTotal = recordedHighAlchTotal;
    }

    public int getItemId()
    {
        return itemId;
    }

    public String getItemName()
    {
        return itemName == null ? "Unknown item" : itemName;
    }

    public long getQuantity()
    {
        return quantity;
    }

    /** Most recently observed nonzero GE unit price for this item. */
    public long getUnitPrice()
    {
        return unitPrice;
    }

    /** RuneLite's item-definition high-alchemy value per item. */
    public long getHighAlchPrice()
    {
        return highAlchPrice;
    }

    public long getTimestamp()
    {
        return timestamp;
    }

    /** Sum of the estimated GE value captured across all confirmed drops of this item. */
    public long getTotalValue()
    {
        return recordedGeTotal != null ? Math.max(0L, recordedGeTotal)
            : multiplySaturated(quantity, unitPrice);
    }

    /** Estimated high-alchemy value for the accumulated quantity of this item. */
    public long getTotalHighAlchValue()
    {
        return recordedHighAlchTotal != null ? Math.max(0L, recordedHighAlchTotal)
            : multiplySaturated(quantity, highAlchPrice);
    }

    /** Combine two cumulative/incremental records for the same item across all time. */
    public DropRecord merge(DropRecord other)
    {
        Objects.requireNonNull(other, "other");
        if (itemId != other.itemId)
        {
            throw new IllegalArgumentException("Only records for the same item can be merged");
        }

        long mergedQuantity = addSaturated(quantity, other.quantity);
        long mergedGeTotal = addSaturated(getTotalValue(), other.getTotalValue());
        // High-alch value is item-definition data, so recompute it for the combined quantity.
        // This also fills the value for older records created before high-alch was stored.
        long latestHaPrice = other.highAlchPrice > 0 ? other.highAlchPrice : highAlchPrice;
        long mergedHaTotal = multiplySaturated(mergedQuantity, latestHaPrice);
        boolean otherIsNewer = other.timestamp >= timestamp;
        long latestGePrice = otherIsNewer
            ? (other.unitPrice > 0 ? other.unitPrice : unitPrice)
            : (unitPrice > 0 ? unitPrice : other.unitPrice);
        String preferredName = otherIsNewer ? other.itemName : itemName;
        String fallbackName = otherIsNewer ? itemName : other.itemName;
        String latestName = preferredName == null || preferredName.trim().isEmpty()
            ? (fallbackName == null || fallbackName.trim().isEmpty() ? getItemName() : fallbackName)
            : preferredName;

        return new DropRecord(itemId, latestName, mergedQuantity, latestGePrice, latestHaPrice,
            Math.max(timestamp, other.timestamp), mergedGeTotal, mergedHaTotal);
    }

    /** Recalculate high-alchemy value from current RuneLite item metadata without changing GE totals. */
    public DropRecord withHighAlchPrice(long newHighAlchPrice)
    {
        long safePrice = Math.max(0L, newHighAlchPrice);
        return new DropRecord(itemId, getItemName(), quantity, unitPrice, safePrice, timestamp,
            getTotalValue(), multiplySaturated(quantity, safePrice));
    }

    public boolean isValid()
    {
        return itemId > 0 && itemName != null && !itemName.trim().isEmpty()
            && quantity > 0 && unitPrice >= 0 && highAlchPrice >= 0 && timestamp >= 0
            && (recordedGeTotal == null || recordedGeTotal >= 0)
            && (recordedHighAlchTotal == null || recordedHighAlchTotal >= 0);
    }

    private static long multiplySaturated(long left, long right)
    {
        if (left <= 0 || right <= 0)
        {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right)
        {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static long addSaturated(long left, long right)
    {
        if (right > 0 && left > Long.MAX_VALUE - right)
        {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
