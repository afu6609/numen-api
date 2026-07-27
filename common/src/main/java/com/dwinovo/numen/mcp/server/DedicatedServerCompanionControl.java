package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.api.ServerNumenActuator;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Dedicated-server MCP adapter. It can drive live or dormant bodies without an
 * owner client; creation/dismissal stay behind server commands during the first
 * private-server slice.
 */
@com.dwinovo.numen.api.Internal
public final class DedicatedServerCompanionControl implements CompanionControl {

    private final MinecraftServer server;

    public DedicatedServerCompanionControl(MinecraftServer server) {
        if (server == null) throw new IllegalArgumentException("server is required");
        this.server = server;
    }

    @Override
    public CompletableFuture<List<Companion>> companions() {
        return ServerNumenActuator.companions(server).thenApply(list -> list.stream()
                .map(item -> new Companion(
                        item.uuid(), item.name(), item.owner(), item.live()))
                .toList());
    }

    @Override
    public CompletableFuture<Boolean> create(String name) {
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public CompletableFuture<Boolean> delete(UUID companion) {
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public CompletableFuture<String> invoke(UUID companion, String toolName, String argsJson) {
        return ServerNumenActuator.invoke(server, companion, toolName, argsJson);
    }

    @Override
    public boolean supportsLifecycleChanges() {
        return false;
    }

    @Override
    public boolean supportsServerChat() {
        return true;
    }

    @Override
    public List<ServerBrainEvents.Event> pollServerEvents(int limit) {
        return ServerBrainEvents.poll(limit);
    }

    @Override
    public CompletableFuture<Boolean> sendChat(UUID companion, String message) {
        return ServerNumenActuator.say(server, companion, message);
    }
}
