/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.fs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Normalization for the paths programs hand to a {@code filesystem} component.
 * <p>
 * Everything a machine passes in is untrusted, and the obvious attack is
 * {@code "../../../../server.properties"}. Rather than trying to detect that after resolving
 * against a real directory, which is where symlinks and platform quirks creep in, paths are
 * canonicalized here first and any attempt to climb above the root is rejected outright.
 */
public final class FilePath {
    private FilePath() {
    }

    /**
     * Canonicalizes a path into slash separated segments with no leading slash, no {@code .} and
     * no {@code ..}.
     *
     * @param path the path to normalize.
     * @return the normalized path; the empty string for the root.
     * @throws IllegalArgumentException if the path climbs above the root.
     */
    public static String normalize(final String path) {
        final Deque<String> segments = new ArrayDeque<>();

        for (final String segment : path.replace('\\', '/').split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    throw new IllegalArgumentException("path escapes the file system root");
                }
                segments.removeLast();
                continue;
            }
            segments.addLast(segment);
        }

        return String.join("/", segments);
    }

    /**
     * The individual segments of a normalized path.
     */
    public static List<String> segments(final String path) {
        final List<String> result = new ArrayList<>();
        for (final String segment : normalize(path).split("/")) {
            if (!segment.isEmpty()) {
                result.add(segment);
            }
        }
        return result;
    }

    /**
     * The parent of a normalized path, or the empty string if it is directly under the root.
     */
    public static String parent(final String normalizedPath) {
        final int index = normalizedPath.lastIndexOf('/');
        return index < 0 ? "" : normalizedPath.substring(0, index);
    }

    /**
     * The last segment of a normalized path.
     */
    public static String name(final String normalizedPath) {
        final int index = normalizedPath.lastIndexOf('/');
        return index < 0 ? normalizedPath : normalizedPath.substring(index + 1);
    }
}
