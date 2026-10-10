package com.droptracker;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.StandardOpenOption;

public class DropHistoryStoreTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Filepath directory()
    {
        // This is test-only setup: production code obtains a sandboxed path through Plugin.getPluginDirectory().
        return Filepath.Unchecked.getRooted(temporaryFolder.getRoot().toPath());
    }

    private DropHistoryStore store()
    {
        return new DropHistoryStore(directory(), new Gson());
    }

    private static String readText(Filepath path) throws IOException
    {
        StringBuilder result = new StringBuilder();
        char[] buffer = new char[2048];
        try (Reader reader = path.openReader())
        {
            int count;
            while ((count = reader.read(buffer)) != -1)
            {
                result.append(buffer, 0, count);
            }
        }
        return result.toString();
    }

    @Test
    public void savesAndLoadsRecords() throws IOException
    {
        DropHistoryStore store = store();
        List<DropRecord> records = Arrays.asList(
            new DropRecord(995, "Coins", 100, 1, 1000L),
            new DropRecord(1511, "Logs", 3, 120, 2000L));

        store.save(records);
        List<DropRecord> loaded = store.load();

        Assert.assertEquals(2, loaded.size());
        Assert.assertEquals("Coins", loaded.get(0).getItemName());
        Assert.assertEquals(100, loaded.get(0).getQuantity());
        Assert.assertEquals("Logs", loaded.get(1).getItemName());
        Assert.assertEquals(360L, loaded.get(1).getTotalValue());
    }

    @Test
    public void missingFileLoadsAsEmpty() throws IOException
    {
        Assert.assertTrue(store().load().isEmpty());
    }

    @Test
    public void exportsAndImportsVersionedJson() throws IOException
    {
        DropHistoryStore store = store();
        Filepath exportFile = directory().joinSegment("backup.json");
        List<DropRecord> records = Arrays.asList(
            new DropRecord(995, "Coins", 100, 1, 1000L),
            new DropRecord(1511, "Logs", 3, 120, 2000L));

        store.exportTo(exportFile, records);

        String json = readText(exportFile);
        Assert.assertTrue(json.contains("\"schemaVersion\": 1"));
        Assert.assertTrue(json.contains("\"records\""));
        Assert.assertEquals(2, store.readImport(exportFile).size());
        Assert.assertFalse(directory().joinSegment("dropped-items.json").exists());
    }

    @Test
    public void exportCannotOverwriteLocalHistory() throws IOException
    {
        DropHistoryStore store = store();
        Filepath localFile = directory().joinSegment("dropped-items.json");
        List<DropRecord> records = Arrays.asList(new DropRecord(995, "Coins", 100, 1, 1000L));
        store.save(records);
        String original = readText(localFile);

        try
        {
            store.exportTo(localFile, records);
            Assert.fail("Expected export to refuse overwriting local history");
        }
        catch (IOException expected)
        {
            Assert.assertTrue(expected.getMessage().contains("different file"));
        }
        Assert.assertEquals(original, readText(localFile));
    }

    @Test
    public void invalidImportIsRejectedWithoutChangingTheSource() throws IOException
    {
        Filepath importFile = directory().joinSegment("invalid.json");
        String invalid = "{\"schemaVersion\":1,\"records\":[{\"itemId\":-1}]}";
        importFile.write(invalid, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
        String original = readText(importFile);

        try
        {
            store().readImport(importFile);
            Assert.fail("Expected invalid import to be rejected");
        }
        catch (IOException expected)
        {
            Assert.assertTrue(expected.getMessage().contains("Invalid drop record"));
        }
        Assert.assertEquals(original, readText(importFile));
    }

    @Test
    public void deleteRemovesTheLocalHistoryFile() throws IOException
    {
        DropHistoryStore store = store();
        Filepath file = directory().joinSegment("dropped-items.json");
        store.save(Arrays.asList(new DropRecord(995, "Coins", 100, 1, 1000L)));
        Assert.assertTrue(file.exists());

        store.delete();

        Assert.assertFalse(file.exists());
        Assert.assertTrue(store.load().isEmpty());
    }

    @Test
    public void corruptFileIsQuarantinedRatherThanSilentlyOverwritten() throws IOException
    {
        Filepath file = directory().joinSegment("dropped-items.json");
        file.write("{ not valid json", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);

        Assert.assertTrue(store().load().isEmpty());
        Assert.assertFalse(file.exists());
        try (Stream<Filepath> paths = directory().walk())
        {
            Assert.assertTrue(paths.anyMatch(path ->
                path.getFileName().startsWith("dropped-items.json.corrupt-")));
        }
    }
}
