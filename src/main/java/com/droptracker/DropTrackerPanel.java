package com.droptracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.LinkBrowser;

/** Readable, card-based Swing panel for items deliberately dropped by the player. */
public final class DropTrackerPanel extends PluginPanel
{
    private static final String GITHUB_URL = "https://github.com/aliozertekin/drop-tracker";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
        .withZone(ZoneId.systemDefault());
    private static final NumberFormat NUMBER_FORMAT = NumberFormat.getIntegerInstance(Locale.US);

    private final ItemManager itemManager;
    private final DropTrackerConfig config;
    private final Supplier<List<DropRecord>> historySupplier;
    private final Runnable clearHistoryAction;
    private final Consumer<Integer> removeItemAction;
    private final Consumer<Filepath> exportAction;
    private final BiConsumer<Filepath, Boolean> importAction;
    private final Consumer<Integer> ignoreItemAction;
    private final Consumer<Boolean> warningPreferenceAction;
    private final DefaultListModel<DropRecord> listModel = new DefaultListModel<>();
    private final JList<DropRecord> itemList = new JList<>(listModel);
    private final JScrollPane scrollPane;
    private final JComboBox<SortOrder> sortOrder = new JComboBox<>(SortOrder.values());
    private final JLabel countLabel = new JLabel("Item types: 0");
    private final JLabel quantityLabel = new JLabel("Items dropped: 0");
    private final JLabel valueLabel = new JLabel("GE value: —");
    private final JLabel highAlchValueLabel = new JLabel("High-alchemy value: —");
    private final JLabel emptyLabel = new JLabel("No items dropped yet. Drop an inventory item to record it.",
        SwingConstants.CENTER);
    private final JPanel summary = new JPanel(new GridLayout(0, 1, 2, 2));
    private final Map<Integer, javax.swing.Icon> iconCache = new HashMap<>();
    private final Timer refreshTimer;
    private List<DropRecord> lastRenderedRows = new ArrayList<>();
    private int lastFontSize = -1;

    public DropTrackerPanel(ItemManager itemManager, DropTrackerConfig config,
        Supplier<List<DropRecord>> historySupplier, Runnable clearHistoryAction, Consumer<Integer> removeItemAction,
        Consumer<Filepath> exportAction, BiConsumer<Filepath, Boolean> importAction, Consumer<Integer> ignoreItemAction, Consumer<Boolean> warningPreferenceAction)
    {
        super(false);
        setLayout(new BorderLayout(6, 6));
        this.itemManager = itemManager;
        this.config = config;
        this.historySupplier = historySupplier;
        this.clearHistoryAction = clearHistoryAction;
        this.removeItemAction = removeItemAction;
        this.exportAction = exportAction;
        this.importAction = importAction;
        this.ignoreItemAction = ignoreItemAction;
        this.warningPreferenceAction = warningPreferenceAction;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setPreferredSize(new Dimension(285, 620));

        JLabel title = new JLabel("Items Dropped");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 2f));
        JButton githubButton = new JButton("GitHub project / feedback");
        githubButton.setToolTipText(GITHUB_URL);
        githubButton.addActionListener(e -> LinkBrowser.browse(GITHUB_URL));

        JPanel header = new JPanel(new BorderLayout(4, 5));
        header.add(title, BorderLayout.NORTH);
        header.add(githubButton, BorderLayout.CENTER);
        summary.setBorder(BorderFactory.createEmptyBorder(4, 0, 2, 0));
        summary.add(countLabel);
        summary.add(quantityLabel);
        summary.add(valueLabel);
        summary.add(highAlchValueLabel);
        header.add(summary, BorderLayout.SOUTH);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        add(header, BorderLayout.NORTH);

        JPanel controls = new JPanel(new GridLayout(0, 2, 4, 4));
        controls.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        JButton exportButton = new JButton("Export JSON…");
        exportButton.addActionListener(e -> chooseExportFile());
        JButton importButton = new JButton("Import JSON…");
        importButton.addActionListener(e -> chooseImportFile());
        JButton removeButton = new JButton("Remove selected");
        removeButton.addActionListener(e -> removeSelectedItem());
        JButton ignoreButton = new JButton("Ignore selected ID");
        ignoreButton.setToolTipText("Add the selected item ID to Ignore item IDs in plugin settings");
        ignoreButton.addActionListener(e -> ignoreSelectedItem());
        JButton clearButton = new JButton("Delete all history");
        clearButton.addActionListener(e -> confirmClear());
        controls.add(new JLabel("Sort:"));
        controls.add(sortOrder);
        controls.add(exportButton);
        controls.add(importButton);
        controls.add(removeButton);
        controls.add(ignoreButton);
        controls.add(clearButton);

        itemList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        itemList.setCellRenderer(new DropRecordRenderer());
        itemList.setFixedCellHeight(calculateRowHeight());
        itemList.setVisibleRowCount(-1);
        itemList.setBackground(itemList.getBackground());
        scrollPane = new JScrollPane(itemList);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);

        emptyLabel.setBorder(BorderFactory.createEmptyBorder(16, 8, 16, 8));
        JPanel body = new JPanel(new BorderLayout(4, 4));
        body.add(controls, BorderLayout.NORTH);
        body.add(scrollPane, BorderLayout.CENTER);
        // Show the empty message above the scroll list without taking space when rows exist.
        JPanel center = new JPanel(new BorderLayout(2, 2));
        center.add(emptyLabel, BorderLayout.NORTH);
        center.add(body, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        sortOrder.addActionListener(e -> {
            lastRenderedRows = new ArrayList<>();
            refresh();
        });
        refreshTimer = new Timer(1800, e -> refresh());
        refreshTimer.setCoalesce(true);
    }

    public void start()
    {
        refreshTimer.start();
        refresh();
    }

    public void stop()
    {
        refreshTimer.stop();
    }

    public void refresh()
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            SwingUtilities.invokeLater(this::refresh);
            return;
        }

        applyConfiguredFontSize();
        summary.setVisible(config.showSummaryTotals());
        List<DropRecord> rows = new ArrayList<>(historySupplier.get());
        SortOrder order = (SortOrder) sortOrder.getSelectedItem();
        if (order == SortOrder.HIGHEST_VALUE)
        {
            rows.sort(Comparator.comparingLong(DropRecord::getTotalValue).reversed()
                .thenComparing(Comparator.comparingLong(DropRecord::getTimestamp).reversed()));
        }
        else if (order == SortOrder.HIGHEST_ALCH)
        {
            rows.sort(Comparator.comparingLong(DropRecord::getTotalHighAlchValue).reversed()
                .thenComparing(Comparator.comparingLong(DropRecord::getTimestamp).reversed()));
        }
        else if (order == SortOrder.ITEM_NAME)
        {
            rows.sort(Comparator.comparing(DropRecord::getItemName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Comparator.comparingLong(DropRecord::getTimestamp).reversed()));
        }
        else if (order == SortOrder.QUANTITY)
        {
            rows.sort(Comparator.comparingLong(DropRecord::getQuantity).reversed()
                .thenComparing(Comparator.comparingLong(DropRecord::getTimestamp).reversed()));
        }
        else
        {
            rows.sort(Comparator.comparingLong(DropRecord::getTimestamp).reversed());
        }

        long totalGeValue = 0L;
        long totalHighAlchValue = 0L;
        long totalQuantity = 0L;
        for (DropRecord row : rows)
        {
            totalGeValue = saturatingAdd(totalGeValue, row.getTotalValue());
            totalHighAlchValue = saturatingAdd(totalHighAlchValue, row.getTotalHighAlchValue());
            totalQuantity = saturatingAdd(totalQuantity, row.getQuantity());
        }
        countLabel.setText("Item types: " + NUMBER_FORMAT.format(rows.size()));
        quantityLabel.setText("Items dropped: " + NUMBER_FORMAT.format(totalQuantity));
        valueLabel.setText("GE value: " + (totalGeValue == 0 ? "—" : NUMBER_FORMAT.format(totalGeValue) + " gp"));
        highAlchValueLabel.setText("High-alchemy value: "
            + (totalHighAlchValue == 0 ? "—" : NUMBER_FORMAT.format(totalHighAlchValue) + " gp"));
        quantityLabel.setVisible(config.showQuantity());
        valueLabel.setVisible(config.showGrandExchangeValue());
        highAlchValueLabel.setVisible(config.showHighAlchValue());

        boolean changed = !sameRows(rows, lastRenderedRows);
        if (changed)
        {
            int oldSelectedId = itemList.getSelectedValue() == null ? -1 : itemList.getSelectedValue().getItemId();
            int oldScroll = scrollPane.getVerticalScrollBar().getValue();
            listModel.clear();
            for (DropRecord record : rows)
            {
                listModel.addElement(record);
            }
            lastRenderedRows = new ArrayList<>(rows);
            if (oldSelectedId > 0)
            {
                for (int i = 0; i < rows.size(); i++)
                {
                    if (rows.get(i).getItemId() == oldSelectedId)
                    {
                        itemList.setSelectedIndex(i);
                        break;
                    }
                }
            }
            SwingUtilities.invokeLater(() -> {
                scrollPane.getVerticalScrollBar().setValue(oldScroll);
            });
        }
        itemList.setFixedCellHeight(calculateRowHeight());
        emptyLabel.setVisible(rows.isEmpty());
        scrollPane.setVisible(!rows.isEmpty());
        itemList.repaint();
    }

    private static boolean sameRows(List<DropRecord> left, List<DropRecord> right)
    {
        if (left.size() != right.size())
        {
            return false;
        }
        for (int i = 0; i < left.size(); i++)
        {
            DropRecord a = left.get(i);
            DropRecord b = right.get(i);
            if (a.getItemId() != b.getItemId() || a.getQuantity() != b.getQuantity()
                || a.getTimestamp() != b.getTimestamp() || a.getTotalValue() != b.getTotalValue()
                || a.getTotalHighAlchValue() != b.getTotalHighAlchValue()
                || !Objects.equals(a.getItemName(), b.getItemName()))
            {
                return false;
            }
        }
        return true;
    }

    private int calculateRowHeight()
    {
        int fontSize = Math.max(10, Math.min(18, config.fontSize()));
        int lines = 0;
        if (config.showQuantity()) lines++;
        if (config.showGrandExchangeValue()) lines++;
        if (config.showHighAlchValue()) lines++;
        if (config.showLastDropped()) lines++;
        return Math.max(36, 14 + fontSize + lines * (fontSize + 4));
    }

    private void applyConfiguredFontSize()
    {
        int size = Math.max(10, Math.min(18, config.fontSize()));
        if (size == lastFontSize)
        {
            return;
        }
        lastFontSize = size;
        applyFontRecursively(this, size);
        itemList.setFixedCellHeight(calculateRowHeight());
        itemList.setCellRenderer(new DropRecordRenderer());
    }

    private static void applyFontRecursively(Component component, int size)
    {
        Font current = component.getFont();
        if (current != null)
        {
            component.setFont(current.deriveFont((float) size));
        }
        if (component instanceof java.awt.Container)
        {
            for (Component child : ((java.awt.Container) component).getComponents())
            {
                applyFontRecursively(child, size);
            }
        }
    }

    private void chooseExportFile()
    {
        List<Filepath> selections = new Filepath.Chooser()
            .setIsSave()
            .setAcceptsFiles()
            .setDialogTitle("Export Drop Tracker history as JSON")
            .addExtensionFilter("Drop Tracker JSON (*.json)", "json")
            .setDefaultExtension("json")
            .setFileName("drop-tracker-export.json")
            .showDialog(this);
        if (selections == null || selections.isEmpty()) return;

        Filepath selected = selections.get(0);
        if (selected.exists())
        {
            if (!confirmWithNeverShowAgain("Confirm JSON export", "Replace the existing file?\n" + selected)) return;
        }
        exportAction.accept(selected);
    }

    private void chooseImportFile()
    {
        List<Filepath> selections = new Filepath.Chooser()
            .setIsOpen()
            .setAcceptsFiles()
            .setDialogTitle("Import Drop Tracker history from JSON")
            .addExtensionFilter("Drop Tracker JSON (*.json)", "json")
            .showDialog(this);
        if (selections == null || selections.isEmpty()) return;

        Filepath selected = selections.get(0);
        Object[] options = {"Merge (recommended)", "Replace current history", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this,
            "Choose how to import the selected file. Merge keeps existing entries and skips exact duplicates.\n"
                + "Replace discards the current history and imports only the selected file.",
            "Import Drop Tracker history", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
            null, options, options[0]);
        if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) return;
        boolean replace = choice == 1;
        if (replace)
        {
            if (!confirmWithNeverShowAgain("Confirm history replacement",
                "This will replace your current history with the selected file. Continue?")) return;
        }
        importAction.accept(selected, replace);
    }

    public void showOperationMessage(String title, String message, int messageType)
    {
        Runnable show = () -> JOptionPane.showMessageDialog(this, message, title, messageType);
        if (SwingUtilities.isEventDispatchThread()) show.run();
        else SwingUtilities.invokeLater(show);
    }

    private void removeSelectedItem()
    {
        DropRecord selected = itemList.getSelectedValue();
        if (selected == null)
        {
            JOptionPane.showMessageDialog(this, "Select an item row first.", "Remove item",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        boolean confirmed = confirmWithNeverShowAgain("Remove item history",
            "Remove all recorded drops of " + selected.getItemName() + " from history?\n"
                + "This does not prevent future drops of this item from being tracked.");
        if (confirmed)
        {
            removeItemAction.accept(selected.getItemId());
            lastRenderedRows = new ArrayList<>();
            refresh();
        }
    }

    private void confirmClear()
    {
        boolean confirmed = confirmWithNeverShowAgain("Delete all history",
            "Delete all locally saved item-drop history? This cannot be undone.");
        if (confirmed)
        {
            clearHistoryAction.run();
            lastRenderedRows = new ArrayList<>();
            refresh();
        }
    }

    private boolean confirmWithNeverShowAgain(String title, String message)
    {
        if (config.skipWarnings())
        {
            return true;
        }
        JCheckBox neverShowAgain = new JCheckBox("Never show these warnings again");
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.add(new JLabel("<html>" + message.replace("\n", "<br>") + "</html>"), BorderLayout.CENTER);
        content.add(neverShowAgain, BorderLayout.SOUTH);
        int answer = JOptionPane.showConfirmDialog(this, content, title,
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (neverShowAgain.isSelected())
        {
            warningPreferenceAction.accept(true);
        }
        return answer == JOptionPane.YES_OPTION;
    }

    private void ignoreSelectedItem()
    {
        DropRecord selected = itemList.getSelectedValue();
        if (selected == null)
        {
            JOptionPane.showMessageDialog(this, "Select an item row first.", "Ignore item",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ignoreItemAction.accept(selected.getItemId());
        JOptionPane.showMessageDialog(this, selected.getItemName() + " (ID " + selected.getItemId()
            + ") was added to Ignore item IDs. Future drops of this item will not be recorded.",
            "Item ignored", JOptionPane.INFORMATION_MESSAGE);
    }

    private javax.swing.Icon iconFor(int itemId)
    {
        if (iconCache.containsKey(itemId)) return iconCache.get(itemId);
        try
        {
            BufferedImage image = itemManager.getImage(itemId);
            if (image == null) return null;
            javax.swing.Icon icon = new ImageIcon(image);
            iconCache.put(itemId, icon);
            return icon;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static long saturatingAdd(long left, long right)
    {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private final class DropRecordRenderer extends JPanel implements ListCellRenderer<DropRecord>
    {
        private final JLabel icon = new JLabel();
        private final JLabel name = new JLabel();
        private final JPanel details = new JPanel(new GridLayout(0, 1, 0, 1));
        private final JPanel textPanel = new JPanel(new BorderLayout(0, 2));

        DropRecordRenderer()
        {
            super(new BorderLayout(6, 0));
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(5, 4, 5, 4));
            icon.setVerticalAlignment(SwingConstants.TOP);
            textPanel.setOpaque(false);
            details.setOpaque(false);
            textPanel.add(name, BorderLayout.NORTH);
            textPanel.add(details, BorderLayout.CENTER);
            add(icon, BorderLayout.WEST);
            add(textPanel, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends DropRecord> list, DropRecord record,
            int index, boolean isSelected, boolean cellHasFocus)
        {
            Color background = isSelected ? list.getSelectionBackground() : list.getBackground();
            Color foreground = isSelected ? list.getSelectionForeground() : list.getForeground();
            setBackground(background);
            icon.setIcon(iconFor(record.getItemId()));
            name.setText(record.getItemName());
            name.setToolTipText(record.getItemName());
            name.setFont(list.getFont().deriveFont(Font.BOLD, (float) Math.max(10, config.fontSize())));
            name.setForeground(foreground);
            icon.setForeground(foreground);
            details.removeAll();
            details.setVisible(false);
            details.setForeground(foreground);
            if (config.showQuantity()) addDetail("Qty: " + NUMBER_FORMAT.format(record.getQuantity()), foreground);
            if (config.showGrandExchangeValue())
            {
                addDetail("GE: " + (record.getTotalValue() == 0 ? "—" : NUMBER_FORMAT.format(record.getTotalValue()) + " gp"), foreground);
            }
            if (config.showHighAlchValue())
            {
                addDetail("High alch: " + (record.getTotalHighAlchValue() == 0 ? "—"
                    : NUMBER_FORMAT.format(record.getTotalHighAlchValue()) + " gp"), foreground);
            }
            if (config.showLastDropped())
            {
                addDetail("Last: " + TIME_FORMAT.format(Instant.ofEpochMilli(record.getTimestamp())), foreground);
            }
            details.setVisible(details.getComponentCount() > 0);
            return this;
        }

        private void addDetail(String text, Color foreground)
        {
            JLabel line = new JLabel(text);
            line.setForeground(foreground);
            line.setFont(getFont() == null ? name.getFont().deriveFont((float) config.fontSize())
                : getFont().deriveFont((float) config.fontSize()));
            details.add(line);
        }
    }

    private enum SortOrder
    {
        NEWEST("Newest"),
        HIGHEST_VALUE("Highest GE value"),
        HIGHEST_ALCH("Highest HA value"),
        ITEM_NAME("Item name"),
        QUANTITY("Quantity");

        private final String label;

        SortOrder(String label)
        {
            this.label = label;
        }

        @Override
        public String toString()
        {
            return label;
        }
    }
}
