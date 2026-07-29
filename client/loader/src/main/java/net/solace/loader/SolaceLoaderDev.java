package net.solace.loader;

import net.runelite.client.RuneLite;

/**
 * IDE / development entry point with RuneLite debug flags.
 *
 * <p>Brings the layer up on the app classloader, so nothing here is hot-reloadable. For the reloadable
 * dev loop use the {@code :devboot} launcher instead; this remains for running straight from an IDE.
 */
public final class SolaceLoaderDev {
    private SolaceLoaderDev() {
    }

    public static void main(String[] args) throws Exception {
        RuneLite.main(new String[]{"--debug", "--developer-mode"});
        DevEntry.start(true);
    }
}
