package li.cil.oc2.common.config.common;

import net.minecraftforge.common.ForgeConfigSpec;

public class CommonSpec {
    public static final ForgeConfigSpec CONFIG_SPEC;
    private static final VMSpec vmSpec;
    private static final LuaMachineSpec luaMachineSpec;
    private static final EnergySpec energySpec;
    private static final GameplaySpec gameplaySpec;
    private static final AdminSpec adminSpec;
    private static final VXLANSpec vxlanSpec;
    private static final InternetCardSpec internetCardSpec;

    static {
        final ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        // VM CONFIGURATION //
        builder.push("vm");
        vmSpec = new VMSpec(builder);
        builder.pop();

        // LUA MACHINE CONFIGURATION //
        builder.push("lua_machine");
        luaMachineSpec = new LuaMachineSpec(builder);
        builder.pop();

        // ENERGY CONFIGURATION //
        builder.push("energy");
        energySpec = new EnergySpec(builder);
        builder.pop();

        // GAMEPLAY CONFIGURATION //
        builder.push("gameplay");
        gameplaySpec = new GameplaySpec(builder);
        builder.pop();

        // ADMIN CONFIGURATION //
        builder.push("admin");
        adminSpec = new AdminSpec(builder);
        builder.pop();

        // VXLAN CONFIGURATION //
        builder.push("vxlan");
        vxlanSpec = new VXLANSpec(builder);
        builder.pop();

        // INTERNET CARD CONFIGURATION //
        builder.push("internet_card");
        internetCardSpec = new InternetCardSpec(builder);
        builder.pop();

        CONFIG_SPEC = builder.build();
    }

    public static void loadValues() {
        // VM CONFIGURATION //
        vmSpec.loadValues();
        // LUA MACHINE CONFIGURATION //
        luaMachineSpec.loadValues();
        // ENERGY CONFIGURATION //
        energySpec.loadValues();
        // GAMEPLAY CONFIGURATION //
        gameplaySpec.loadValues();
        // ADMIN CONFIGURATION //
        adminSpec.loadValues();
        // VXLAN CONFIGURATION //
        vxlanSpec.loadValues();
        // INTERNET CARD CONFIGURATION //
        internetCardSpec.loadValues();
    }
}
