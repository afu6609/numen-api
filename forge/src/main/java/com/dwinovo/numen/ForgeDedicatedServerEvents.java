package com.dwinovo.numen;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.mcp.server.NumenServerMcp;
import com.dwinovo.numen.mcp.server.ServerBrainEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Dedicated-server lifecycle and chat bridge for the external Momo brain.
 *
 * <p>Using Forge's static event subscriber discovery makes this wiring
 * independent of mod-constructor listener inference, including when Numen API
 * is loaded as a jar-in-jar dependency.
 */
@Mod.EventBusSubscriber(
        modid = Constants.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.DEDICATED_SERVER)
public final class ForgeDedicatedServerEvents {

    private ForgeDedicatedServerEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        NumenServerMcp.start(event.getServer(), FMLPaths.CONFIGDIR.get());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        NumenServerMcp.stop();
    }

    /** Queue human chat without cancelling or rewriting the vanilla message. */
    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (player instanceof NumenPlayer) return;
        MinecraftServer server = player.level().getServer();
        long gameTime = server == null ? 0L : server.overworld().getGameTime();
        ServerBrainEvents.publishChat(
                player.getUUID(),
                event.getUsername(),
                event.getRawText(),
                gameTime);
    }
}
