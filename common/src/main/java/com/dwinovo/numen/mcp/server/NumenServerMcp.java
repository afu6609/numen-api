package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.Constants;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;

/**
 * Lifecycle owner for the MCP endpoint in a physical dedicated-server process.
 */
public final class NumenServerMcp {

    private static McpServer server;

    private NumenServerMcp() {}

    public static synchronized void start(MinecraftServer minecraft, Path configDir) {
        if (server != null) return;
        McpConfig cfg = McpConfig.load(configDir.resolve("numen").resolve("mcp_server.json"));
        if (!cfg.enabled()) {
            Constants.LOG.info("[numen-mcp] dedicated-server endpoint disabled in config");
            return;
        }

        McpServer candidate = new McpServer(
                cfg, new DedicatedServerCompanionControl(minecraft));
        try {
            candidate.start();
            server = candidate;
            Constants.LOG.info(
                    "[numen-mcp] dedicated-server endpoint up on http://{}:{}/mcp",
                    cfg.host(), cfg.port());
        } catch (Exception ex) {
            Constants.LOG.error(
                    "[numen-mcp] dedicated-server endpoint failed on {}:{} — {}",
                    cfg.host(), cfg.port(), ex.toString());
        }
    }

    public static synchronized void stop() {
        if (server == null) return;
        server.stop();
        server = null;
        Constants.LOG.info("[numen-mcp] dedicated-server endpoint stopped");
    }
}
