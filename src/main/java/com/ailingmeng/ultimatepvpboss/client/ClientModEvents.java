package com.ailingmeng.ultimatepvpboss.client;

import com.ailingmeng.ultimatepvpboss.UltimatePvpBoss;
import com.ailingmeng.ultimatepvpboss.registry.ModEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = UltimatePvpBoss.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {
    private ClientModEvents() {}

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.PVP_BOSS.get(), PvpBossRenderer::new);
    }

    // RenderTickEvent belongs to the FORGE bus, not the MOD bus used above. The nested
    // client-only subscriber also keeps Minecraft classes off dedicated servers.
    @Mod.EventBusSubscriber(modid = UltimatePvpBoss.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class RenderDiagnostics {
        private static final RenderStallWatchdog WATCHDOG =
                new RenderStallWatchdog(System::nanoTime, message -> UltimatePvpBoss.LOGGER.warn(message));
        private static boolean bossSeen;

        private RenderDiagnostics() { }

        static void bossRendering() {
            if (!bossSeen) {
                bossSeen = true;
                WATCHDOG.heartbeat(true);
                WATCHDOG.start();
            }
        }

        @SubscribeEvent
        public static void renderTick(TickEvent.RenderTickEvent event) {
            if (Minecraft.getInstance().level == null) bossSeen = false;
            // START and END both count, including paused/menu frames. No thread dumps,
            // filesystem IO, network access or blocking waits occur on the render thread.
            WATCHDOG.heartbeat(bossSeen);
        }
    }
}
