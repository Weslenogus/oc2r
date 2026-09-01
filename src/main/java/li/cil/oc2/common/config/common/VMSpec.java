package li.cil.oc2.common.config.common;

import li.cil.oc2.common.Constants;
import li.cil.oc2.common.config.Config;
import net.minecraftforge.common.ForgeConfigSpec;

public class VMSpec {
    public final ForgeConfigSpec.LongValue maxAllocatedMemory;
    public final ForgeConfigSpec.IntValue diskSizeFactor;

    VMSpec(ForgeConfigSpec.Builder builder) {
        maxAllocatedMemory = builder.comment(
            "Maximum memory that can be allocated across all virtual machines (computers/robots) at any one time (in bytes)"
        ).defineInRange("maxAllocatedMemory", 512 * Constants.MEGABYTE, 0, Long.MAX_VALUE);

        diskSizeFactor = builder.comment(
            "Determines the size factor of drives, where SF is the size factor set below the sizes are as follows (this settings is in bytes):",
            "Small Disk: SF",
            "Medium Disk: 2 * SF",
            "Large Disk: 4 * SF",
            "Extra Large Disk: 16 * SF",
            "With the default factor this is equivalent to (in the same order) 8MB, 16MB, 32MB, 128MB.",
            "Drives are memory mapped files in the world save, so a larger factor costs disk space for",
            "the drives that are actually made, and nothing at all for the ones that are not."
        ).defineInRange("diskSizeFactor", 8 * Constants.MEGABYTE, 0, Integer.MAX_VALUE);
    }

    public void loadValues() {
        Config.maxAllocatedMemory = maxAllocatedMemory.get();
        Config.diskSizeFactor = diskSizeFactor.get();
    }
}
