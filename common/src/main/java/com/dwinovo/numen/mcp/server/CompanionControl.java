package com.dwinovo.numen.mcp.server;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Side-neutral control surface used by the MCP transport.
 *
 * <p>The original implementation talked directly to the owner-client
 * {@code NumenActuator}. A dedicated server needs the same MCP protocol but a
 * different execution route, so the HTTP/JSON-RPC layer depends on this small
 * interface instead of either physical side.
 */
@com.dwinovo.numen.api.Internal
public interface CompanionControl {

    record Companion(UUID uuid, String name, UUID owner, boolean live) {}

    CompletableFuture<List<Companion>> companions();

    CompletableFuture<Boolean> create(String name);

    CompletableFuture<Boolean> delete(UUID companion);

    CompletableFuture<String> invoke(UUID companion, String toolName, String argsJson);

    /** Dedicated-server mode initially keeps lifecycle changes command/config driven. */
    default boolean supportsLifecycleChanges() {
        return true;
    }

    /** Server chat ingress/egress exists only when MCP is hosted by a dedicated server. */
    default boolean supportsServerChat() {
        return false;
    }

    default List<ServerBrainEvents.Event> pollServerEvents(int limit) {
        return List.of();
    }

    default CompletableFuture<Boolean> sendChat(UUID companion, String message) {
        return CompletableFuture.completedFuture(false);
    }

    /** Restricted operator commands exist only in dedicated-server mode. */
    default boolean supportsServerCommands() {
        return false;
    }

    default CompletableFuture<String> runRestrictedCommand(
            UUID companion,
            String command) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("server commands are unavailable"));
    }
}
