package com.droptracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("droptracker")
public interface DropTrackerConfig extends Config
{
    @Range(min = 100, max = 100000)
    @ConfigItem(
        keyName = "historyLimit",
        name = "History entries",
        description = "Maximum number of distinct item rows retained locally",
        position = 0
    )
    default int historyLimit()
    {
        return 10000;
    }

    @Range(min = 10, max = 18)
    @ConfigItem(
        keyName = "fontSize",
        name = "Panel font size",
        description = "Font size used by the Drop Tracker panel (10–18 pt)",
        position = 1
    )
    default int fontSize()
    {
        return 15;
    }

    @ConfigItem(
        keyName = "showSummaryTotals",
        name = "Show summary totals",
        description = "Show total item count, quantity, GE value, and high-alchemy value at the top",
        position = 2
    )
    default boolean showSummaryTotals()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showQuantity",
        name = "Show quantity",
        description = "Show the cumulative quantity for each item",
        position = 3
    )
    default boolean showQuantity()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showGrandExchangeValue",
        name = "Show GE value",
        description = "Show estimated Grand Exchange value",
        position = 4
    )
    default boolean showGrandExchangeValue()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showHighAlchValue",
        name = "Show high-alchemy value",
        description = "Show estimated high-alchemy value",
        position = 5
    )
    default boolean showHighAlchValue()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showLastDropped",
        name = "Show last dropped date",
        description = "Show the most recent drop date and time in day/month/year format",
        position = 6
    )
    default boolean showLastDropped()
    {
        return true;
    }

    @ConfigItem(
        keyName = "ignoredItemIds",
        name = "Ignore item IDs",
        description = "Comma-separated item IDs that should not be tracked when dropped (for example: 995, 554)",
        position = 7
    )
    default String ignoredItemIds()
    {
        return "";
    }
    @ConfigItem(
        keyName = "skipWarnings",
        name = "Skip destructive-action warnings",
        description = "When enabled, skip confirmation dialogs for deleting history, removing item history, and replacing imported data",
        position = 8
    )
    default boolean skipWarnings()
    {
        return false;
    }

}
