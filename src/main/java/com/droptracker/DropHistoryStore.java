package com.droptracker;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.util.Filepath;

/** Versioned, atomic JSON persistence for locally recorded drops. */
public final class DropHistoryStore
{
    private static final String HISTORY_FILE_NAME = "dropped-items.json";
    private static final int CURRENT_SCHEMA_VERSION = 1;
    private static final long MAX_IMPORT_BYTES = 25L * 1024L * 1024L;
    private static final int MAX_IMPORT_RECORDS = 100_000;

    private final Filepath directory;
    private final Filepath file;
    private final Gson gson;

    public DropHistoryStore(Filepath directory, Gson gson)
    {
        this.directory = directory;
        this.file = directory.joinSegment(HISTORY_FILE_NAME);
        // Preserve RuneLite's registered Gson adapters while formatting user-readable JSON.
        this.gson = gson.newBuilder().setPrettyPrinting().create();
    }

    /**
     * Read a user-selected export without changing or quarantining the source file.
     * Imports are strict: reject malformed documents or records instead of silently
     * importing only part of the user's data.
     */
    public synchronized List<DropRecord> readImport(Filepath source) throws IOException
    {
        if (source == null || !source.isFile())
        {
            throw new IOException("The selected import file does not exist or is not a regular file.");
        }
        if (source.size() > MAX_IMPORT_BYTES)
        {
            throw new IOException("The selected JSON file is too large to import safely.");
        }

        final HistoryDocument document;
        try (Reader reader = source.openReader())
        {
            document = gson.fromJson(reader, HistoryDocument.class);
        }
        catch (JsonParseException | IllegalStateException e)
        {
            throw new IOException("The selected file is not valid Drop Tracker JSON.", e);
        }

        if (document == null)
        {
            throw new IOException("The selected JSON file is empty.");
        }
        if (document.schemaVersion != CURRENT_SCHEMA_VERSION)
        {
            throw new IOException("Unsupported Drop Tracker JSON schema version: " + document.schemaVersion
                + ". This version supports schema " + CURRENT_SCHEMA_VERSION + ".");
        }
        if (document.records == null)
        {
            throw new IOException("The JSON file is missing its records array.");
        }
        if (document.records.size() > MAX_IMPORT_RECORDS)
        {
            throw new IOException("The JSON file contains too many records to import safely.");
        }

        List<DropRecord> result = new ArrayList<>();
        for (int i = 0; i < document.records.size(); i++)
        {
            DropRecord record = document.records.get(i);
            if (record == null || !record.isValid())
            {
                throw new IOException("Invalid drop record at index " + i + "; no data was imported.");
            }
            result.add(record);
        }
        return result;
    }

    /** Export records using the same versioned JSON format used for local persistence. */
    public synchronized void exportTo(Filepath destination, List<DropRecord> records) throws IOException
    {
        if (destination == null)
        {
            throw new IOException("No export destination was selected.");
        }
        if (destination.equals(file))
        {
            throw new IOException("Choose a different file so the local history cannot be overwritten by an export.");
        }

        directory.createDirectories();
        Filepath temporary = directory.createTempFile("export-", ".tmp");
        try
        {
            writeDocument(temporary, records);
            // The chooser returns a rooted Filepath for the selected destination. Copying through
            // Filepath keeps the write within the user-approved selection and avoids raw Path I/O.
            temporary.copyTo(destination, StandardCopyOption.REPLACE_EXISTING);
        }
        finally
        {
            temporary.deleteIfExists();
        }
    }

    /** Delete the local history file. The next confirmed drop will create it again. */
    public synchronized void delete() throws IOException
    {
        file.deleteIfExists();
    }

    public synchronized List<DropRecord> load() throws IOException
    {
        if (!file.exists())
        {
            return new ArrayList<>();
        }

        final HistoryDocument document;
        try (Reader reader = file.openReader())
        {
            document = gson.fromJson(reader, HistoryDocument.class);
        }
        catch (JsonParseException | IllegalStateException e)
        {
            quarantineCorruptFile(e);
            return new ArrayList<>();
        }

        if (document == null)
        {
            quarantineCorruptFile(new IOException("History JSON was empty"));
            return new ArrayList<>();
        }
        if (document.schemaVersion > CURRENT_SCHEMA_VERSION)
        {
            throw new IOException("History schema " + document.schemaVersion
                + " is newer than this plugin supports; refusing to overwrite it");
        }

        List<DropRecord> result = new ArrayList<>();
        if (document.records != null)
        {
            for (DropRecord record : document.records)
            {
                if (record != null && record.isValid())
                {
                    result.add(record);
                }
            }
        }
        return result;
    }

    public synchronized void save(List<DropRecord> records) throws IOException
    {
        directory.createDirectories();
        Filepath temporary = directory.createTempFile("history-", ".tmp");
        try
        {
            writeDocument(temporary, records);
            try
            {
                temporary.moveTo(file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e)
            {
                temporary.moveTo(file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally
        {
            temporary.deleteIfExists();
        }
    }

    private void writeDocument(Filepath destination, List<DropRecord> records) throws IOException
    {
        HistoryDocument document = new HistoryDocument();
        document.schemaVersion = CURRENT_SCHEMA_VERSION;
        document.records = new ArrayList<>();
        if (records != null)
        {
            for (DropRecord record : records)
            {
                if (record != null && record.isValid())
                {
                    document.records.add(record);
                }
            }
        }

        try (Writer writer = destination.openWriter(
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))
        {
            gson.toJson(document, writer);
            writer.write(System.lineSeparator());
        }
    }

    private void quarantineCorruptFile(Exception original) throws IOException
    {
        Filepath quarantine = directory.joinSegment(
            file.getFileName() + ".corrupt-" + Instant.now().toEpochMilli());
        try
        {
            file.moveTo(quarantine, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException moveFailure)
        {
            moveFailure.addSuppressed(original);
            throw new IOException("History could not be read and could not be preserved at " + quarantine,
                moveFailure);
        }
    }

    private static final class HistoryDocument
    {
        // Intentionally no field initializers: imports must prove these fields were present.
        // Local loading remains backward-tolerant for older files that omitted a schema version.
        private int schemaVersion;
        private List<DropRecord> records;
    }
}
