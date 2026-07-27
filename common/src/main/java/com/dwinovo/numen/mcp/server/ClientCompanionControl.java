package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.api.NumenActuator;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Owner-client adapter preserving Numen's original MCP behavior. */
@com.dwinovo.numen.api.Internal
public final class ClientCompanionControl implements CompanionControl {

    @Override
    public CompletableFuture<List<Companion>> companions() {
        return NumenActuator.companions().thenApply(list -> list.stream()
                .map(item -> new Companion(item.uuid(), item.name(), null, true))
                .toList());
    }

    @Override
    public CompletableFuture<Boolean> create(String name) {
        return NumenActuator.create(name);
    }

    @Override
    public CompletableFuture<Boolean> delete(UUID companion) {
        return NumenActuator.delete(companion);
    }

    @Override
    public CompletableFuture<String> invoke(UUID companion, String toolName, String argsJson) {
        return NumenActuator.invoke(companion, toolName, argsJson);
    }
}
