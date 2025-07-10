package li.cil.oc2.common.bus.device.provider.item;

import li.cil.oc2.api.bus.device.ItemDevice;
import li.cil.oc2.api.bus.device.provider.ItemDeviceQuery;
import li.cil.oc2.common.bus.device.vm.item.AbstractNetworkInterfaceDevice;
import li.cil.oc2.common.config.Config;
import li.cil.oc2.common.bus.device.vm.item.InternetCardDevice;
import li.cil.oc2.common.bus.device.provider.util.AbstractItemDeviceProvider;
import li.cil.oc2.common.item.Items;

import java.util.Optional;

public final class InternetCardItemDeviceProvider extends AbstractItemDeviceProvider {
    public InternetCardItemDeviceProvider() {
        super(Items.INTERNET_CARD);
    }

    @Override
    protected Optional<ItemDevice> getItemDevice(final ItemDeviceQuery query) {
        // Only provide the device if internet card is enabled in config
        if (!Config.internetCardEnabled) {
            return Optional.empty();
        }

        return Optional.of(new InternetCardDevice(query.getItemStack()));
    }

    @Override
    protected int getItemDeviceEnergyConsumption(final ItemDeviceQuery query) {
        // Use the new internet card specific energy consumption setting
        return Config.internetCardEnergyPerTick;
    }
}
