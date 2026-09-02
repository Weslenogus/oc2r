/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.lua;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Where the machine's Lua comes from: the mod's resources, or a directory on disk.
 * <p>
 * Everything a Lua machine runs before a player's own code - the BIOS, the kernel, the shell in
 * ROM - ships as a resource, which means changing a line of it costs a rebuild and a restart of
 * the game. That is a poor loop for the part of this mod that gets edited most.
 * <p>
 * So point {@code -Doc2r.lua.scriptRoot=/path/to/src/main/resources/assets/oc2r/lua} at the working
 * tree and every one of those files is read from there instead, freshly, each time a machine
 * starts. Edit {@code rom/init.lua}, type {@code reboot} at the prompt, and the change is running:
 * no rebuild, no restart, no world reload.
 * <p>
 * Nothing falls over if the directory is incomplete - a file that is not there is read from the
 * resources as usual, so the override can hold just the one file being worked on. The property is
 * read once, because moving the directory under a running game is not a thing worth supporting.
 */
public final class LuaScriptSource {
    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Directory to read the machine's Lua from instead of the mod's resources.
     */
    public static final String OVERRIDE_PROPERTY = "oc2r.lua.scriptRoot";

    /**
     * The resource directory the override stands in for. A resource path is mapped to a file by
     * taking whatever follows this.
     */
    private static final String RESOURCE_ROOT = "/assets/oc2r/lua";

    @Nullable private static final Path ROOT = resolveRoot();

    private LuaScriptSource() {
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Whether scripts are being read from disk. Only interesting for logging: every reader goes
     * through {@link #open} either way.
     */
    public static boolean isOverridden() {
        return ROOT != null;
    }

    /**
     * Opens one of the machine's scripts by its resource path, from the override directory when it
     * has one and from the mod's resources otherwise.
     *
     * @param resource the resource path, e.g. {@code /assets/oc2r/lua/bios.lua}.
     * @throws IOException if neither has it.
     */
    public static InputStream open(final String resource) throws IOException {
        final Path file = onDisk(resource);
        if (file != null) {
            return Files.newInputStream(file);
        }

        final InputStream stream = LuaScriptSource.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IOException("missing resource [" + resource + "]");
        }
        return stream;
    }

    public static byte[] readBytes(final String resource) throws IOException {
        try (final InputStream stream = open(resource)) {
            return stream.readAllBytes();
        }
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * The file backing a resource path, or {@code null} if the resources should answer instead.
     */
    @Nullable
    private static Path onDisk(final String resource) {
        if (ROOT == null || !resource.startsWith(RESOURCE_ROOT + "/")) {
            return null;
        }

        final Path file;
        try {
            file = ROOT.resolve(resource.substring(RESOURCE_ROOT.length() + 1)).normalize();
        } catch (final InvalidPathException e) {
            return null;
        }

        // Anything that climbed out of the override directory is not the override directory's, and
        // a resource path is not something a player controls, so this can only be a mistake here.
        if (!file.startsWith(ROOT) || !Files.isRegularFile(file)) {
            return null;
        }

        return file;
    }

    @Nullable
    private static Path resolveRoot() {
        final String value = System.getProperty(OVERRIDE_PROPERTY, "").trim();
        if (value.isEmpty()) {
            return null;
        }

        try {
            final Path path = Path.of(value).toAbsolutePath().normalize();
            if (!Files.isDirectory(path)) {
                LOGGER.warn("{} is set to [{}], which is not a directory; the machine's Lua will "
                    + "be read from the mod's resources.", OVERRIDE_PROPERTY, value);
                return null;
            }

            LOGGER.info("Reading the machine's Lua from [{}]; a machine restart picks up edits "
                + "without rebuilding the mod.", path);
            return path;
        } catch (final InvalidPathException e) {
            LOGGER.warn("{} is set to [{}], which is not a path.", OVERRIDE_PROPERTY, value);
            return null;
        }
    }
}
