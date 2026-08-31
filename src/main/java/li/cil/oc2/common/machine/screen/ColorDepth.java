/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.screen;

/**
 * The colour depths an OpenComputers 1 screen can run at, one per screen tier.
 */
public enum ColorDepth {
    /**
     * Tier 1: black and white.
     */
    ONE_BIT(1),
    /**
     * Tier 2: sixteen colours, all of them from the editable palette.
     */
    FOUR_BIT(4),
    /**
     * Tier 3: 256 colours, the first sixteen from the editable palette and the remaining 240 a
     * fixed 6x8x5 cube. This is what MineOS expects.
     */
    EIGHT_BIT(8);

    private final int bits;

    ColorDepth(final int bits) {
        this.bits = bits;
    }

    /**
     * The depth in bits, which is what {@code gpu.getDepth} reports.
     */
    public int getBits() {
        return bits;
    }

    /**
     * Whether this depth is at most as deep as the given one, used to validate
     * {@code gpu.setDepth}.
     */
    public boolean isAtMost(final ColorDepth other) {
        return bits <= other.bits;
    }

    public static ColorDepth fromBits(final int bits) {
        return switch (bits) {
            case 1 -> ONE_BIT;
            case 4 -> FOUR_BIT;
            case 8 -> EIGHT_BIT;
            default -> throw new IllegalArgumentException("unsupported depth");
        };
    }
}
