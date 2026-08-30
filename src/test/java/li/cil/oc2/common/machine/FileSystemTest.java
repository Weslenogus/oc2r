/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine;

import li.cil.oc2.common.machine.fs.FilePath;
import li.cil.oc2.common.machine.fs.NioFileSystem;
import li.cil.oc2.common.machine.fs.RamFileSystem;
import li.cil.oc2.common.machine.fs.VirtualFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FileSystemTest {
    @Test
    void normalizesPaths() {
        assertEquals("a/b", FilePath.normalize("/a/b"));
        assertEquals("a/b", FilePath.normalize("a//b/"));
        assertEquals("a/b", FilePath.normalize("./a/./b"));
        assertEquals("a", FilePath.normalize("a/b/.."));
        assertEquals("", FilePath.normalize("/"));
        assertEquals("a/b", FilePath.normalize("\\a\\b"));
    }

    @Test
    void refusesPathsThatClimbAboveTheRoot() {
        // The obvious attack from inside a machine, and the reason paths are canonicalized before
        // they are resolved against anything real.
        assertThrows(IllegalArgumentException.class, () -> FilePath.normalize(".."));
        assertThrows(IllegalArgumentException.class, () -> FilePath.normalize("/../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> FilePath.normalize("a/../../b"));
    }

    @Test
    void splitsPathsIntoSegments() {
        assertEquals(List.of("a", "b", "c"), FilePath.segments("/a/b/c"));
        assertEquals(List.of(), FilePath.segments("/"));
        assertEquals("a/b", FilePath.parent("a/b/c"));
        assertEquals("c", FilePath.name("a/b/c"));
        assertEquals("", FilePath.parent("c"));
    }

    ///////////////////////////////////////////////////////////////////

    private static void exercise(final VirtualFileSystem fs) throws IOException {
        assertFalse(fs.exists("/notes.txt"));

        final int handle = fs.open("/notes.txt", VirtualFileSystem.Mode.WRITE);
        assertTrue(fs.write(handle, "hello ".getBytes(StandardCharsets.UTF_8)));
        assertTrue(fs.write(handle, "world".getBytes(StandardCharsets.UTF_8)));
        fs.close(handle);

        assertTrue(fs.exists("/notes.txt"));
        assertEquals(11, fs.size("/notes.txt"));
        assertFalse(fs.isDirectory("/notes.txt"));

        final int reader = fs.open("/notes.txt", VirtualFileSystem.Mode.READ);
        assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8),
            fs.read(reader, Long.MAX_VALUE));
        assertNull(fs.read(reader, Long.MAX_VALUE), "a second read past the end must return nil");

        assertEquals(6, fs.seek(reader, "set", 6));
        assertArrayEquals("world".getBytes(StandardCharsets.UTF_8), fs.read(reader, Long.MAX_VALUE));
        assertEquals(11, fs.seek(reader, "end", 0));
        assertEquals(4, fs.seek(reader, "set", 4));
        assertEquals(6, fs.seek(reader, "cur", 2));
        fs.close(reader);

        assertTrue(fs.makeDirectory("/deep/nested/dir"));
        assertTrue(fs.isDirectory("/deep/nested"));

        final String[] entries = fs.list("/");
        assertNotNull(entries);
        // Directories are reported with a trailing slash, which is how a program tells them apart.
        assertTrue(Arrays.asList(entries).contains("deep/"), Arrays.toString(entries));
        assertTrue(Arrays.asList(entries).contains("notes.txt"), Arrays.toString(entries));

        assertTrue(fs.rename("/notes.txt", "/deep/moved.txt"));
        assertFalse(fs.exists("/notes.txt"));
        assertTrue(fs.exists("/deep/moved.txt"));

        assertTrue(fs.remove("/deep"));
        assertFalse(fs.exists("/deep/moved.txt"));
        assertNull(fs.list("/nope"), "listing a missing path returns nil rather than empty");
    }

    @Test
    void ramFileSystemBehavesLikeAFileSystem() throws IOException {
        exercise(new RamFileSystem(1 << 20));
    }

    @Test
    void nioFileSystemBehavesLikeAFileSystem(@TempDir final Path root) throws IOException {
        exercise(new NioFileSystem(root.resolve("disk"), false, 1 << 20));
    }

    @Test
    void appendModeStartsAtTheEnd() throws IOException {
        final VirtualFileSystem fs = new RamFileSystem(1 << 16);
        final int write = fs.open("/log", VirtualFileSystem.Mode.WRITE);
        fs.write(write, "one".getBytes(StandardCharsets.UTF_8));
        fs.close(write);

        final int append = fs.open("/log", VirtualFileSystem.Mode.APPEND);
        fs.write(append, "two".getBytes(StandardCharsets.UTF_8));
        fs.close(append);

        final int read = fs.open("/log", VirtualFileSystem.Mode.READ);
        assertArrayEquals("onetwo".getBytes(StandardCharsets.UTF_8), fs.read(read, Long.MAX_VALUE));
        fs.close(read);
    }

    @Test
    void writeModeTruncates() throws IOException {
        final VirtualFileSystem fs = new RamFileSystem(1 << 16);
        int handle = fs.open("/log", VirtualFileSystem.Mode.WRITE);
        fs.write(handle, "a long line".getBytes(StandardCharsets.UTF_8));
        fs.close(handle);

        handle = fs.open("/log", VirtualFileSystem.Mode.WRITE);
        fs.write(handle, "short".getBytes(StandardCharsets.UTF_8));
        fs.close(handle);

        assertEquals(5, fs.size("/log"));
    }

    @Test
    void readsAreCappedSoOneCallCannotAllocateTheHeap() throws IOException {
        final VirtualFileSystem fs = new RamFileSystem(1 << 20);
        final int write = fs.open("/big", VirtualFileSystem.Mode.WRITE);
        fs.write(write, new byte[VirtualFileSystem.MAX_READ_SIZE * 2]);
        fs.close(write);

        final int read = fs.open("/big", VirtualFileSystem.Mode.READ);
        final byte[] first = fs.read(read, Long.MAX_VALUE);
        assertNotNull(first);
        assertEquals(VirtualFileSystem.MAX_READ_SIZE, first.length,
            "math.huge must not mean 'allocate whatever the file is'");
        fs.close(read);
    }

    @Test
    void enforcesTheCapacityLimit() throws IOException {
        final VirtualFileSystem fs = new RamFileSystem(1024);
        final int handle = fs.open("/fill", VirtualFileSystem.Mode.WRITE);
        assertThrows(IOException.class, () -> fs.write(handle, new byte[2048]));
        fs.close(handle);
    }

    @Test
    void refusesToLeaveItsRoot(@TempDir final Path root) throws IOException {
        final Path disk = root.resolve("disk");
        final VirtualFileSystem fs = new NioFileSystem(disk, false, 1 << 20);
        Files.writeString(root.resolve("secret.txt"), "not yours");

        // Queries answer no rather than raising; anything that would act on the path refuses.
        assertFalse(fs.exists("../secret.txt"));
        assertThrows(IllegalArgumentException.class,
            () -> fs.open("../secret.txt", VirtualFileSystem.Mode.READ));
        assertThrows(IllegalArgumentException.class, () -> fs.remove("../secret.txt"));
        assertTrue(Files.exists(root.resolve("secret.txt")));
    }

    @Test
    void readOnlyFileSystemsRefuseWrites(@TempDir final Path root) throws IOException {
        final Path disk = root.resolve("disk");
        Files.createDirectories(disk);
        Files.writeString(disk.resolve("readme"), "hello");

        final VirtualFileSystem fs = new NioFileSystem(disk, true, 1 << 20);
        assertTrue(fs.isReadOnly());
        assertTrue(fs.exists("/readme"));
        assertThrows(IOException.class, () -> fs.open("/new", VirtualFileSystem.Mode.WRITE));
        assertThrows(IOException.class, () -> fs.remove("/readme"));
        assertThrows(IOException.class, () -> fs.makeDirectory("/dir"));
    }

    @Test
    void limitsHowManyHandlesOneMachineCanHold() throws IOException {
        final VirtualFileSystem fs = new RamFileSystem(1 << 20);
        for (int i = 0; i < 16; i++) {
            fs.open("/f" + i, VirtualFileSystem.Mode.WRITE);
        }
        assertThrows(IOException.class, () -> fs.open("/one-too-many", VirtualFileSystem.Mode.WRITE));

        fs.closeAll();
        // Once released, the machine can open files again.
        fs.close(fs.open("/after", VirtualFileSystem.Mode.WRITE));
    }

    @Test
    void rejectsOperationsOnAClosedHandle() throws IOException {
        final VirtualFileSystem fs = new RamFileSystem(1 << 16);
        final int handle = fs.open("/f", VirtualFileSystem.Mode.WRITE);
        fs.close(handle);
        assertThrows(IOException.class, () -> fs.read(handle, 1));
        assertThrows(IOException.class, () -> fs.close(handle));
    }

    @Test
    void parsesTheModeStringsProgramsActuallyPass() {
        assertEquals(VirtualFileSystem.Mode.READ, VirtualFileSystem.Mode.parse("r"));
        assertEquals(VirtualFileSystem.Mode.READ, VirtualFileSystem.Mode.parse("rb"));
        assertEquals(VirtualFileSystem.Mode.WRITE, VirtualFileSystem.Mode.parse("wb"));
        assertEquals(VirtualFileSystem.Mode.APPEND, VirtualFileSystem.Mode.parse("a"));
        assertThrows(IllegalArgumentException.class, () -> VirtualFileSystem.Mode.parse("x"));
    }
}
