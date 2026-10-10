package com.droptracker;

import com.google.inject.Provides;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.JOptionPane;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.ImageUtil;

@PluginDescriptor(
    name = "Drop Tracker",
    description = "Tracks cumulative quantities, Grand Exchange value, and high-alchemy value for items you drop",
    tags = {"drop", "items", "uim", "value", "history", "high-alchemy", "grand-exchange"},
    internalName = "drop-tracker",
    legacyDataDirectory = "drop-tracker"
)
public class DropTrackerPlugin extends Plugin
{
    private static final Logger LOGGER = Logger.getLogger(DropTrackerPlugin.class.getName());

    @Inject
    private Client client;

    @Inject
    private Gson gson;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ItemManager itemManager;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private DropTrackerConfig config;

    @Inject
    private ConfigManager configManager;

    private final Object historyLock = new Object();
    private final List<DropRecord> history = new ArrayList<>();
    private DropHistoryStore historyStore;
    private DropTrackerPanel panel;
    private NavigationButton navigationButton;
    private ExecutorService persistenceExecutor;
    private volatile boolean persistenceAvailable = true;
    private final DropConfirmationTracker dropConfirmationTracker = new DropConfirmationTracker();
    // Accessed from RuneLite client-thread callbacks only after startup has scheduled initialization.
    private boolean inventoryBaselineInitialized;

    @Provides
    DropTrackerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(DropTrackerConfig.class);
    }

    @Override
    protected void startUp() throws IOException
    {
        // RuneLite migrates the earlier .runelite/drop-tracker directory into this plugin-owned folder.
        // Use a distinct filename so received-loot records are never misrepresented as deliberate drops.
        Filepath pluginDirectory = getPluginDirectory();
        pluginDirectory.createDirectories();
        historyStore = new DropHistoryStore(pluginDirectory, gson);
        persistenceAvailable = true;
        inventoryBaselineInitialized = false;
        dropConfirmationTracker.reset(Collections.<Integer, Integer>emptyMap());
        // Plugin startUp runs on the plugin manager thread, not the RuneLite client thread.
        // Client API calls such as getItemContainer must only happen on the client thread.
        boolean normalizedHistory = false;
        synchronized (historyLock)
        {
            history.clear();
            try
            {
                List<DropRecord> loaded = historyStore.load();
                List<DropRecord> aggregated = DropHistoryAggregator.aggregateByItem(loaded);
                normalizedHistory = aggregated.size() != loaded.size();
                history.addAll(aggregated);
                trimHistoryLocked();
                normalizedHistory = normalizedHistory || history.size() != aggregated.size();
            }
            catch (IOException e)
            {
                persistenceAvailable = false;
                LOGGER.log(Level.WARNING,
                    "Could not load Drop Tracker history. Existing data will not be overwritten this session.", e);
            }
        }

        persistenceExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "drop-tracker-persistence");
            thread.setDaemon(true);
            return thread;
        });
        if (normalizedHistory && persistenceAvailable)
        {
            // Persist migration from per-drop/daily rows to one cumulative row per item, keeping the same export format.
            persistAsync();
        }

        panel = new DropTrackerPanel(itemManager, config, this::snapshotHistory, this::clearHistory,
            this::removeItemFromHistory, this::exportHistory, this::importHistory, this::ignoreItemId,
            enabled -> configManager.setConfiguration("droptracker", "skipWarnings", enabled));
        navigationButton = NavigationButton.builder()
            .tooltip("Drop Tracker")
            .icon(ImageUtil.loadImageResource(getClass(), "icon.png"))
            .priority(5)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navigationButton);
        panel.start();
        // Schedule client API work only after history and the persistence executor are ready.
        clientThread.invokeLater(this::initializeInventoryBaseline);
    }

    @Override
    protected void shutDown()
    {
        dropConfirmationTracker.reset(Collections.<Integer, Integer>emptyMap());
        inventoryBaselineInitialized = false;
        if (panel != null)
        {
            panel.stop();
        }
        if (navigationButton != null)
        {
            clientToolbar.removeNavigation(navigationButton);
            navigationButton = null;
        }

        if (persistenceExecutor != null)
        {
            persistFinalStateAsync();
            persistenceExecutor.shutdown();
            try
            {
                if (!persistenceExecutor.awaitTermination(5, TimeUnit.SECONDS))
                {
                    persistenceExecutor.shutdownNow();
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                persistenceExecutor.shutdownNow();
            }
            persistenceExecutor = null;
        }
        panel = null;
    }

    /**
     * Record only explicit inventory Drop actions. A menu click alone is not enough:
     * the item must subsequently disappear or decrease in the inventory container.
     */
    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (!"drop".equalsIgnoreCase(stripMenuTags(event.getMenuOption())))
        {
            return;
        }
        MenuAction action = event.getMenuAction();
        if (action != MenuAction.CC_OP && action != MenuAction.CC_OP_LOW_PRIORITY)
        {
            return;
        }

        ItemContainer inventory = client.getItemContainer(InventoryID.INV);
        if (inventory == null)
        {
            return;
        }
        if (!inventoryBaselineInitialized)
        {
            // The plugin may have been enabled while logged out or before the first inventory event.
            // Establish a baseline before tracking this explicit Drop action.
            dropConfirmationTracker.reset(inventoryCounts(inventory));
            inventoryBaselineInitialized = true;
        }

        int slot = event.getParam0();
        Widget widget = event.getWidget();
        if (widget != null && widget.getIndex() >= 0)
        {
            slot = widget.getIndex();
        }
        if (slot < 0 || slot >= inventory.size())
        {
            return;
        }

        Item item = inventory.getItem(slot);
        if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
        {
            return;
        }
        int clickedItemId = event.getItemId();
        if (clickedItemId > 0 && clickedItemId != item.getId())
        {
            return;
        }

        int itemId = item.getId();
        if (isIgnoredItem(itemId))
        {
            return;
        }
        String itemName;
        long unitPrice;
        long highAlchPrice;
        try
        {
            net.runelite.api.ItemComposition composition = itemManager.getItemComposition(itemId);
            itemName = composition.getName();
            unitPrice = Math.max(0L, itemManager.getItemPrice(itemId));
            highAlchPrice = Math.max(0L, composition.getHaPrice());
            // RuneLite's built-in value displays account for these currency items explicitly.
            if (itemId == 995) // Coins
            {
                highAlchPrice = 1L;
            }
            else if (itemId == 13204) // Platinum token
            {
                highAlchPrice = 1000L;
            }
        }
        catch (RuntimeException e)
        {
            LOGGER.log(Level.FINE, "Could not resolve metadata for dropped item " + itemId, e);
            itemName = "Item " + itemId;
            unitPrice = 0;
            highAlchPrice = 0;
        }
        if (itemName == null || itemName.trim().isEmpty())
        {
            itemName = "Item " + itemId;
        }

        dropConfirmationTracker.queue(itemId, itemName, item.getQuantity(), unitPrice, highAlchPrice,
            System.currentTimeMillis(), inventoryCounts(inventory));
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (event.getContainerId() != InventoryID.INV)
        {
            return;
        }
        Map<Integer, Integer> currentCounts = inventoryCounts(event.getItemContainer());
        if (!inventoryBaselineInitialized)
        {
            // Ignore the first observed inventory state; without a baseline it cannot prove a drop.
            dropConfirmationTracker.reset(currentCounts);
            inventoryBaselineInitialized = true;
            return;
        }
        List<DropRecord> confirmed = dropConfirmationTracker.inventoryChanged(currentCounts);
        if (confirmed.isEmpty())
        {
            return;
        }
        boolean added = false;
        synchronized (historyLock)
        {
            for (DropRecord record : confirmed)
            {
                if (isIgnoredItem(record.getItemId()))
                {
                    continue;
                }
                DropHistoryAggregator.addOrMerge(history, record);
                added = true;
            }
            if (added)
            {
                trimHistoryLocked();
            }
        }
        if (!added)
        {
            return;
        }
        persistAsync();
        if (panel != null)
        {
            panel.refresh();
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (!inventoryBaselineInitialized && client.getGameState() == GameState.LOGGED_IN)
        {
            initializeInventoryBaseline();
        }
        dropConfirmationTracker.gameTick();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            // GameStateChanged is delivered on the client thread, so the inventory API is safe here.
            initializeInventoryBaseline();
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
        {
            dropConfirmationTracker.reset(Collections.<Integer, Integer>emptyMap());
            inventoryBaselineInitialized = false;
        }
    }

    /** Must only be called on RuneLite's client thread. */
    private void initializeInventoryBaseline()
    {
        if (client.getGameState() == GameState.LOGGED_IN)
        {
            dropConfirmationTracker.reset(inventoryCounts(client.getItemContainer(InventoryID.INV)));
            inventoryBaselineInitialized = true;
            refreshHistoryHighAlchPrices();
        }
        else
        {
            dropConfirmationTracker.reset(Collections.<Integer, Integer>emptyMap());
            inventoryBaselineInitialized = false;
        }
    }

    /** Refresh high-alchemy values for existing rows on the client thread, preserving captured GE totals. */
    private void refreshHistoryHighAlchPrices()
    {
        boolean changed = false;
        synchronized (historyLock)
        {
            for (int i = 0; i < history.size(); i++)
            {
                DropRecord record = history.get(i);
                long highAlchPrice;
                try
                {
                    highAlchPrice = Math.max(0L, itemManager.getItemComposition(record.getItemId()).getHaPrice());
                    if (record.getItemId() == 995) // Coins
                    {
                        highAlchPrice = 1L;
                    }
                    else if (record.getItemId() == 13204) // Platinum token
                    {
                        highAlchPrice = 1000L;
                    }
                }
                catch (RuntimeException e)
                {
                    LOGGER.log(Level.FINE, "Could not refresh high-alch value for item " + record.getItemId(), e);
                    continue;
                }

                // Also refresh when the unit price already matches but the stored aggregate total is from an older
                // format. High-alch value is fixed for a given item definition, so it is unit value × quantity.
                if (record.getHighAlchPrice() != highAlchPrice
                    || record.getTotalHighAlchValue() != safeMultiply(record.getQuantity(), highAlchPrice))
                {
                    history.set(i, record.withHighAlchPrice(highAlchPrice));
                    changed = true;
                }
            }
        }
        if (changed)
        {
            persistAsync();
            if (panel != null)
            {
                panel.refresh();
            }
        }
    }

    private static long safeMultiply(long left, long right)
    {
        if (left <= 0 || right <= 0)
        {
            return 0L;
        }
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    private static Map<Integer, Integer> inventoryCounts(ItemContainer container)
    {
        Map<Integer, Integer> counts = new HashMap<>();
        if (container == null || container.getItems() == null)
        {
            return counts;
        }
        for (Item item : container.getItems())
        {
            if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
            {
                continue;
            }
            counts.merge(item.getId(), item.getQuantity(), (left, right) -> {
                long total = (long) left + right;
                return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
            });
        }
        return counts;
    }

    private static String stripMenuTags(String value)
    {
        return value == null ? "" : value.replaceAll("<[^>]*>", "").trim();
    }

    private List<DropRecord> snapshotHistory()
    {
        synchronized (historyLock)
        {
            return new ArrayList<>(history);
        }
    }

    private void trimHistoryLocked()
    {
        int limit = Math.max(100, Math.min(100000, config.historyLimit()));
        while (history.size() > limit)
        {
            history.remove(0);
        }
    }

    /** Remove all accumulated history for one item, without changing the tracking exclusion settings. */
    private void removeItemFromHistory(int itemId)
    {
        if (!persistenceAvailable)
        {
            notifyPanel("Remove item unavailable", "History changes are disabled because an earlier storage operation "
                + "failed. Restart RuneLite after checking the log before deleting data.", JOptionPane.ERROR_MESSAGE);
            return;
        }
        boolean removed = false;
        synchronized (historyLock)
        {
            for (int i = history.size() - 1; i >= 0; i--)
            {
                if (history.get(i).getItemId() == itemId)
                {
                    history.remove(i);
                    removed = true;
                }
            }
        }
        if (removed)
        {
            persistAsync();
            if (panel != null)
            {
                panel.refresh();
            }
            notifyPanel("Item removed", "This item's accumulated history was removed. To stop future entries, add its item ID to Ignore item IDs in the plugin settings.", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void ignoreItemId(int itemId)
    {
        String current = config.ignoredItemIds();
        if (current != null)
        {
            for (String token : current.split("[,;\\s]+"))
            {
                if (token.trim().equals(Integer.toString(itemId))) return;
            }
        }
        String updated = current == null || current.trim().isEmpty() ? Integer.toString(itemId)
            : current.trim() + ", " + itemId;
        configManager.setConfiguration("droptracker", "ignoredItemIds", updated);
    }

    /** True when the user has listed the item ID in the config's comma/space-separated exclusion list. */
    private boolean isIgnoredItem(int itemId)
    {
        String ignored = config.ignoredItemIds();
        if (ignored == null || ignored.trim().isEmpty())
        {
            return false;
        }
        for (String token : ignored.split("[,;\\s]+"))
        {
            try
            {
                if (Integer.parseInt(token.trim()) == itemId)
                {
                    return true;
                }
            }
            catch (NumberFormatException ignoredToken)
            {
                // Ignore invalid tokens rather than interrupt tracking.
            }
        }
        return false;
    }

    private void clearHistory()
    {
        final ExecutorService executor = persistenceExecutor;
        if (executor == null || executor.isShutdown())
        {
            notifyPanel("Delete history failed", "The history storage service is not running.",
                JOptionPane.ERROR_MESSAGE);
            return;
        }

        boolean scheduled = false;
        synchronized (historyLock)
        {
            List<DropRecord> previous = new ArrayList<>(history);
            history.clear();
            try
            {
                // Submit while holding historyLock so a concurrent drop cannot queue a save before
                // this deletion and then have its new record erased from disk.
                executor.submit(() -> {
                    try
                    {
                        historyStore.delete();
                        persistenceAvailable = true;
                        notifyPanel("History deleted", "All Drop Tracker history has been deleted.",
                            JOptionPane.INFORMATION_MESSAGE);
                    }
                    catch (IOException deleteFailure)
                    {
                        try
                        {
                            // Best-effort fallback: replace the data with a valid empty document.
                            historyStore.save(Collections.<DropRecord>emptyList());
                            persistenceAvailable = true;
                            notifyPanel("History cleared", "The history file could not be removed, but its records "
                                + "were cleared.", JOptionPane.WARNING_MESSAGE);
                        }
                        catch (IOException saveFailure)
                        {
                            saveFailure.addSuppressed(deleteFailure);
                            persistenceAvailable = false;
                            LOGGER.log(Level.WARNING, "Could not delete or clear Drop Tracker history", saveFailure);
                            notifyPanel("Delete history failed", "Could not safely delete the saved history. "
                                + "The existing file was preserved. See the RuneLite log for details.",
                                JOptionPane.ERROR_MESSAGE);
                        }
                    }
                });
                scheduled = true;
            }
            catch (java.util.concurrent.RejectedExecutionException e)
            {
                history.addAll(previous);
                LOGGER.log(Level.WARNING, "Could not queue Drop Tracker history deletion", e);
            }
        }

        if (panel != null)
        {
            panel.refresh();
        }
        if (!scheduled)
        {
            notifyPanel("Delete history failed", "The history deletion could not be scheduled; your history "
                + "has been restored.", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportHistory(Filepath destination)
    {
        final ExecutorService executor = persistenceExecutor;
        if (executor == null || executor.isShutdown())
        {
            notifyPanel("Export failed", "The history storage service is not running.", JOptionPane.ERROR_MESSAGE);
            return;
        }
        final List<DropRecord> snapshot = snapshotHistory();
        try
        {
            executor.submit(() -> {
                try
                {
                    historyStore.exportTo(destination, snapshot);
                    notifyPanel("Export complete", "Exported " + snapshot.size() + " records to:\n"
                        + destination, JOptionPane.INFORMATION_MESSAGE);
                }
                catch (IOException e)
                {
                    LOGGER.log(Level.WARNING, "Could not export Drop Tracker history", e);
                    notifyPanel("Export failed", "Could not write the selected JSON file. " + e.getMessage(),
                        JOptionPane.ERROR_MESSAGE);
                }
            });
        }
        catch (java.util.concurrent.RejectedExecutionException e)
        {
            notifyPanel("Export failed", "The export could not be scheduled.", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void importHistory(Filepath source, boolean replaceExisting)
    {
        final ExecutorService executor = persistenceExecutor;
        if (executor == null || executor.isShutdown())
        {
            notifyPanel("Import failed", "The history storage service is not running.", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!persistenceAvailable)
        {
            notifyPanel("Import unavailable", "History writes are disabled because a previous storage operation "
                + "failed. Restart RuneLite after checking the log before importing, to avoid overwriting data.",
                JOptionPane.ERROR_MESSAGE);
            return;
        }
        try
        {
            executor.submit(() -> {
                try
                {
                    // Validate the full input before changing current history.
                    List<DropRecord> imported = historyStore.readImport(source);
                    int duplicatesSkipped = 0;
                    int retainedCount;
                    int trimmedCount;
                    synchronized (historyLock)
                    {
                        LinkedHashMap<List<Object>, DropRecord> unique = new LinkedHashMap<>();
                        if (!replaceExisting)
                        {
                            for (DropRecord record : history)
                            {
                                unique.put(recordKey(record), record);
                            }
                        }
                        for (DropRecord record : imported)
                        {
                            List<Object> key = recordKey(record);
                            if (unique.containsKey(key))
                            {
                                duplicatesSkipped++;
                            }
                            else
                            {
                                unique.put(key, record);
                            }
                        }

                        List<DropRecord> combined = DropHistoryAggregator.aggregateByItem(unique.values());
                        int limit = Math.max(100, Math.min(100000, config.historyLimit()));
                        trimmedCount = Math.max(0, combined.size() - limit);
                        if (trimmedCount > 0)
                        {
                            combined = new ArrayList<>(combined.subList(trimmedCount, combined.size()));
                        }

                        // Commit to disk before replacing in-memory history, preserving the current
                        // view if the atomic write fails.
                        try
                        {
                            historyStore.save(combined);
                        }
                        catch (IOException writeFailure)
                        {
                            persistenceAvailable = false;
                            throw writeFailure;
                        }
                        history.clear();
                        history.addAll(combined);
                        retainedCount = history.size();
                    }
                    if (panel != null)
                    {
                        panel.refresh();
                    }
                    clientThread.invokeLater(this::refreshHistoryHighAlchPrices);
                    String message = (replaceExisting ? "Replaced history with " : "Imported ")
                        + imported.size() + " records. " + duplicatesSkipped + " duplicate record(s) skipped; "
                        + retainedCount + " record(s) retained.";
                    if (trimmedCount > 0)
                    {
                        message += " " + trimmedCount + " older record(s) were removed to respect the configured "
                            + "history limit.";
                    }
                    notifyPanel("Import complete", message, JOptionPane.INFORMATION_MESSAGE);
                }
                catch (IOException e)
                {
                    LOGGER.log(Level.WARNING, "Could not import Drop Tracker history", e);
                    notifyPanel("Import failed", "No imported data was applied. " + e.getMessage(),
                        JOptionPane.ERROR_MESSAGE);
                }
                catch (RuntimeException e)
                {
                    LOGGER.log(Level.WARNING, "Unexpected error while importing Drop Tracker history", e);
                    notifyPanel("Import failed", "No imported data was applied because the file could not be "
                        + "processed safely. See the RuneLite log for details.", JOptionPane.ERROR_MESSAGE);
                }
            });
        }
        catch (java.util.concurrent.RejectedExecutionException e)
        {
            notifyPanel("Import failed", "The import could not be scheduled.", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static List<Object> recordKey(DropRecord record)
    {
        return Arrays.<Object>asList(record.getItemId(), record.getItemName(), record.getQuantity(),
            record.getUnitPrice(), record.getHighAlchPrice(), record.getTimestamp(), record.getTotalValue(),
            record.getTotalHighAlchValue());
    }

    private void notifyPanel(String title, String message, int messageType)
    {
        DropTrackerPanel current = panel;
        if (current != null)
        {
            current.showOperationMessage(title, message, messageType);
        }
    }

    private void persistFinalStateAsync()
    {
        final ExecutorService executor = persistenceExecutor;
        if (!persistenceAvailable || executor == null || executor.isShutdown())
        {
            return;
        }
        final List<DropRecord> snapshot = snapshotHistory();
        try
        {
            // Keep a deleted/empty history deleted at shutdown instead of recreating an empty file.
            executor.submit(() -> {
                try
                {
                    if (snapshot.isEmpty())
                    {
                        historyStore.delete();
                    }
                    else
                    {
                        historyStore.save(snapshot);
                    }
                }
                catch (IOException e)
                {
                    persistenceAvailable = false;
                    LOGGER.log(Level.WARNING, "Could not save final Drop Tracker history state", e);
                }
            });
        }
        catch (java.util.concurrent.RejectedExecutionException e)
        {
            LOGGER.log(Level.FINE, "Final history save skipped during shutdown", e);
        }
    }

    private void persistAsync()
    {
        final ExecutorService executor = persistenceExecutor;
        if (!persistenceAvailable || executor == null || executor.isShutdown())
        {
            return;
        }
        final List<DropRecord> snapshot = snapshotHistory();
        try
        {
            executor.submit(() -> {
                try
                {
                    historyStore.save(snapshot);
                }
                catch (IOException e)
                {
                    persistenceAvailable = false;
                    LOGGER.log(Level.WARNING,
                        "Could not save Drop Tracker history. Further writes are disabled for this session.", e);
                }
            });
        }
        catch (java.util.concurrent.RejectedExecutionException e)
        {
            LOGGER.log(Level.FINE, "History save skipped during shutdown", e);
        }
    }
}
