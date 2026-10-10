**Project / support:** https://github.com/aliozertekin/drop-tracker

# Drop Tracker for RuneLite

Drop Tracker records **items you deliberately drop from your own inventory** and estimates the total Grand Exchange value lost. It is intended for UIM-style tracking of dropped items, not for recording loot you receive.

When you choose **Drop** for an inventory item, the plugin remembers the selected item and its quantity. It records the drop only after RuneLite reports an inventory change that confirms the quantity went down. If no matching inventory decrease happens within a few game ticks, the pending action is discarded.

## What it shows

- A local history of confirmed item drops, **collapsed into one cumulative row per item across all time**. Repeated drops of the same item update that existing row, even on later days.
- Item sprite, name, total quantity ever dropped, cumulative estimated GE value, cumulative high-alchemy value, and the most recent drop time in the computer's local time zone.
- All-time totals for estimated GE value and high-alchemy value.
- Sorting by newest, highest GE value, highest high-alchemy value, item name, or quantity.
- A default history limit of 10,000 distinct item rows, configurable up to 100,000.
- JSON export/import for moving or backing up your drop history.
- Merge import (the default), with exact duplicate records skipped, or replace the current history.
- Remove the selected item type from history, or delete all saved history with a confirmation prompt.
- A GitHub project/feedback button opens the source repository.
- Configurable panel font size and toggles for summary totals, quantity, GE value, high-alchemy value, and last-dropped timestamp.
- An optional comma-separated item-ID exclusion list to stop tracking selected items in future drops.

Grand Exchange (GE) prices are estimates from RuneLite's item-price service. High-alchemy values come from RuneLite's item definition. A missing/zero value is shown as unavailable; it does not mean the item has no practical value. Prices are captured when you select Drop and may not reflect the item's actual market value at the time of the drop.

Each row represents one item ID across the entire saved history, regardless of the day it was dropped. The quantity and GE value accumulate as more of that item is dropped. The displayed date/time is always the **most recent confirmed drop** for that item, formatted in the computer's local time zone. GE totals preserve the captured estimate for each confirmed drop; high-alchemy totals use the item's high-alchemy value and accumulated quantity.

## Detection limits

This plugin listens for the inventory's explicit **Drop** menu action and cross-checks the subsequent inventory quantity decrease. It does not record loot you receive, items you trade away, items deposited into a bank, or an item simply disappearing without a matching Drop action. In rare cases where another inventory change for the same item happens at the same time as the requested drop, attribution may be ambiguous. Pending actions expire after five game ticks if no matching inventory decrease occurs.

The plugin records the quantity confirmed as removed from the inventory. Rows are aggregated by item ID across all dates. It does not distinguish intentional UIM drops from ordinary drops, or infer that a ground item was later picked up by another player.

## Panel layout and tracking preferences

Use the plugin settings to choose a panel font size from 10 to 18 points, show or hide summary totals and per-item quantity/GE/high-alchemy/date fields, and supply a comma-separated list of item IDs to ignore (for example, `995, 554`). Ignored IDs are not recorded for future drops; their existing history stays until you select that item and choose **Remove selected**. The panel uses day/month/year (`dd/MM/yyyy HH:mm`) in the local timezone. The **GitHub project / feedback** button opens the project repository.

## Export, import, and delete history

Use the panel controls to manage your data:

- **Export JSON** saves a versioned `schemaVersion`/`records` JSON file to a location you choose. This is ordinary, readable JSON and is not encrypted.
- **Import JSON** validates the complete selected file before changing current history. **Merge** keeps existing records and skips exact duplicates. **Replace** replaces the current history with the selected file after an additional confirmation.
- **Delete all history** removes the local history file. A future confirmed drop starts a new history file.

Imports are limited to 25 MB and 100,000 source records. Invalid records, malformed JSON, or unsupported schema versions are rejected without applying partial data or modifying the selected source file. Imported rows are aggregated into one cumulative row per item across all dates. If an import exceeds your configured history limit, the item rows with the oldest last-drop timestamps are trimmed and the dialog reports how many were removed. The history limit defaults to 10,000 distinct item rows and can be configured up to 100,000. Export a copy before using Replace or Delete if you need a backup.

The locally stored history is also ordinary JSON. Treat exported files and the local data file as personal activity data; anyone who can read them can see what you dropped and the associated values/timestamps.

## Requirements

- JDK 11 or later.
- Gradle 8.10.2 or newer.
- Internet access on first build to download Gradle and RuneLite dependencies.

The included `gradlew` / `gradlew.bat` scripts are lightweight bootstrap helpers, not the official Gradle Wrapper. They download the pinned Gradle 8.10.2 distribution and verify its SHA-256. You can also install Gradle from your distribution and use `gradle` directly.

## Build and test

Linux/CachyOS/macOS:

```bash
chmod +x gradlew
./gradlew test
./gradlew build
```

Windows PowerShell:

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

The generated JAR is under `build/libs/`.

## Test in RuneLite

Launch the development client with:

```bash
./gradlew run
```

(or `./gradlew.bat run` in Windows PowerShell). Sign in using RuneLite's documented development-client flow, then:

1. Open the Drop Tracker sidebar.
2. Right-click an item in your inventory and select **Drop**.
3. Confirm the stack disappears/reduces and then appears in the plugin history.
4. Drop the same item again later (including on a different day) and confirm its quantity/value updates in the same item row and the last-dropped time changes.
5. Confirm GE total, high-alchemy total, and last-drop time are shown correctly.
6. Try clicking an action that is not Drop; it should not be recorded.
7. Try opening a menu and cancelling without dropping; it should not be recorded.
8. Restart the client and confirm that history persists.
9. Export JSON and confirm that the file contains `schemaVersion` and `records` plus the GE/high-alch values.
10. Change the font size and toggle optional fields in settings; confirm the panel responds.
11. Add an item ID to Ignore item IDs, drop that item, and confirm no new history appears.
12. Select an existing item and use Remove selected; confirm only that item row is removed.
13. Import that file with Merge; confirm an identical exported item row is skipped.
14. Import it with Replace after confirming the warning; confirm the history matches the file.
15. Attempt to import malformed JSON and confirm the existing history remains unchanged.
16. Delete all history, restart the client, and confirm the history stays empty.
17. Test a zero/missing-price item and each sorting mode.

For UIM use, validate several item types, including stackable and non-stackable items. In particular, compare the recorded all-time total against the item quantities and price estimates shown in the item list.

## Local data and migration note

Confirmed drops are stored in `.runelite/plugin-data/drop_tracker/dropped-items.json` (or the equivalent RuneLite data directory on your OS). The plugin uses RuneLite's sandboxed `Filepath` API for storage and import/export file selection. On the first startup after this update, RuneLite migrates the legacy `.runelite/drop-tracker` folder into the plugin-owned data directory when possible. This file is ordinary local JSON, not encrypted; do not store anything in it that you consider secret. The plugin deliberately uses a new filename so an earlier prototype's *received loot* history is not mislabelled as items dropped by you.

If history JSON is corrupt, the store attempts to preserve it by renaming it to a `.corrupt-<timestamp>` file before starting a fresh history. Newer unsupported schema versions are not overwritten.

## Plugin Hub submission

The source repository should be public before submitting. The plugin uses RuneLite's `Filepath` utility for persistent storage and for user-selected import/export files, and derives its formatted JSON serializer from RuneLite's injected Gson instance. The Plugin Hub listing icon is `icon.png` at the repository root; the matching bundled navigation icon is under `src/main/resources/com/droptracker/icon.png`.

To submit, fork `https://github.com/runelite/plugin-hub`, create a branch, and add `plugins/drop-tracker` containing the public repository URL and the full 40-character commit hash you want reviewed. Open a pull request and resolve any automated checks or review feedback. See the [official submission guide](https://github.com/runelite/plugin-hub#submitting-a-plugin).

## Build and runtime verification

Before a public release, run `./gradlew test` and `./gradlew build` using RuneLite's real dependencies, and test menu/inventory event behavior in the RuneLite development client. CI runs the automated test and build tasks on pushes and pull requests. A passing build alone does not replace in-game testing.
