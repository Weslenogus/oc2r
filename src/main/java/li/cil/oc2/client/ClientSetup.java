/* SPDX-License-Identifier: MIT */

package li.cil.oc2.client;

import li.cil.oc2.client.gui.*;
import li.cil.oc2.client.item.CustomItemColors;
import li.cil.oc2.client.item.CustomItemModelProperties;
import li.cil.oc2.client.model.BusCableModelLoader;
import li.cil.oc2.client.renderer.BusInterfaceNameRenderer;
import li.cil.oc2.client.renderer.ProjectorDepthRenderer;
import li.cil.oc2.client.renderer.blockentity.*;
import li.cil.oc2.client.renderer.color.BusCableBlockColor;
import li.cil.oc2.client.renderer.entity.RobotRenderer;
import li.cil.oc2.client.renderer.entity.model.RobotModel;
import li.cil.oc2.common.block.Blocks;
import li.cil.oc2.common.blockentity.BlockEntities;
import li.cil.oc2.common.config.Config;
import li.cil.oc2.common.container.Containers;
import li.cil.oc2.common.entity.Entities;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ModelEvent.RegisterGeometryLoaders;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.jetbrains.annotations.Nullable;

@SuppressWarnings("unused")
public final class ClientSetup {
    @Nullable private static Boolean captureInputState = null;

    @SubscribeEvent
    public static void handleSetupEvent(final FMLClientSetupEvent event) {
        BusInterfaceNameRenderer.initialize();

        BlockEntityRenderers.register(BlockEntities.COMPUTER.get(), ComputerRenderer::new);
        BlockEntityRenderers.register(BlockEntities.MONITOR.get(), MonitorRenderer::new);
        BlockEntityRenderers.register(BlockEntities.DISK_DRIVE.get(), DiskDriveRenderer::new);
        BlockEntityRenderers.register(BlockEntities.CHARGER.get(), ChargerRenderer::new);
        BlockEntityRenderers.register(BlockEntities.PROJECTOR.get(), ProjectorRenderer::new);
        BlockEntityRenderers.register(BlockEntities.INTERNET_GATEWAY.get(), InternetGateWayRenderer::new);
        BlockEntityRenderers.register(BlockEntities.LUA_SCREEN.get(), LuaScreenRenderer::new);

        event.enqueueWork(() -> {
            CustomItemModelProperties.initialize();
            CustomItemColors.initialize();

            MenuScreens.register(Containers.COMPUTER.get(), ComputerContainerScreen::new);
            MenuScreens.register(Containers.COMPUTER_TERMINAL.get(), ComputerTerminalScreen::new);
            MenuScreens.register(Containers.MONITOR.get(), MonitorDisplayScreen::new);
            MenuScreens.register(Containers.ROBOT.get(), RobotContainerScreen::new);
            MenuScreens.register(Containers.ROBOT_TERMINAL.get(), RobotTerminalScreen::new);
            MenuScreens.register(Containers.NETWORK_TUNNEL.get(), NetworkTunnelScreen::new);

            // We need to register this manually, because static init throws errors when running data generation.
            MinecraftForge.EVENT_BUS.register(ProjectorDepthRenderer.class);

            // Canvas screens hold a GPU texture each. Leaving them behind on world unload would
            // leak video memory for every server the player visits.
            MinecraftForge.EVENT_BUS.addListener(
                (final net.minecraftforge.event.level.LevelEvent.Unload unload) -> {
                    if (unload.getLevel().isClientSide()) {
                        li.cil.oc2.client.renderer.CanvasPainter.clear();
                    }
                });
        });
    }

    @SubscribeEvent
    public static void handleModelRegistryEvent(final RegisterGeometryLoaders event) {
        if (Blocks.BUS_CABLE.getId() == null) throw new RuntimeException("Null bus cable ID");
        event.register(Blocks.BUS_CABLE.getId().toString().replace("oc2r:", ""), new BusCableModelLoader());
    }

    @SubscribeEvent
    public void renderHotbar(RenderGuiOverlayEvent event) {
        if(event.getOverlay().id() == VanillaGuiOverlay.HOTBAR.id() && KeyboardScreen.hideHotbar) {
            event.setCanceled(true);
        } else if(event.getOverlay().id() == VanillaGuiOverlay.HOTBAR.id()) {
            event.setCanceled(false);
        }
    }

    @SubscribeEvent
    public static void handleColorHandler(final RegisterColorHandlersEvent.Block event) {
        event.register(new BusCableBlockColor(), Blocks.BUS_CABLE.get());
    }

    @SubscribeEvent
    public static void handleEntityRendererRegisterEvent(final EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Entities.ROBOT.get(), RobotRenderer::new);
    }

    @SubscribeEvent
    public static void handleRegisterLayerDefinitionsEvent(final EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(RobotModel.ROBOT_MODEL_LAYER, RobotModel::createRobotLayer);
    }

    public static boolean getCaptureInputState() {
        if (captureInputState == null) {
            captureInputState = Config.captureInputDefaultState;
        }

        return captureInputState;
    }

    public static void setCaptureInputState(final boolean value) {
        captureInputState = value;
    }
}
