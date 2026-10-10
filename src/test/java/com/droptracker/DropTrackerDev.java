package com.droptracker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Development launcher modeled on RuneLite's official example plugin. */
public final class DropTrackerDev
{
    private DropTrackerDev()
    {
    }

    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(DropTrackerPlugin.class);
        RuneLite.main(args);
    }
}
