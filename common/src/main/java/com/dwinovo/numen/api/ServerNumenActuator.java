package com.dwinovo.numen.api;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.entity.CompanionRegistry;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.ServerToolReplies;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dedicated-server entry point for driving Numen companion bodies without an
 * owner's Minecraft client.
 *
 * <p>This is the server-side counterpart of {@link NumenActuator}. It executes
 * against the same {@link ToolRegistry} and the same task scheduler, but routes
 * task results directly to a {@link CompletableFuture}. An MCP server or other
 * private harness running beside Minecraft can therefore perceive and act while
 * every human player is offline.
 *
 * <p>Every public method is safe to call from an HTTP/MCP worker thread. World
 * access is marshalled onto the Minecraft server thread.
 */
public final class ServerNumenActuator {

    private static final AtomicLong SEQ = new AtomicLong();

    private ServerNumenActuator() {}

    /** Persistent companion metadata plus whether its body is currently live. */
    public record Companion(UUID uuid, String name, UUID owner, boolean live) {}

    /**
     * List every registered companion, including dormant bodies whose owners
     * are offline.
     */
    public static CompletableFuture<List<Companion>> companions(MinecraftServer server) {
        CompletableFuture<List<Companion>> result = new CompletableFuture<>();
        if (server == null) {
            result.completeExceptionally(new IllegalArgumentException("server is required"));
            return result;
        }
        server.execute(() -> {
            List<Companion> companions = CompanionRegistry.get(server).snapshot().entrySet().stream()
                    .map(e -> companion(server, e))
                    .sorted(Comparator.comparing(Companion::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            result.complete(companions);
        });
        return result;
    }

    /**
     * Invoke one registered body tool directly on the dedicated server.
     *
     * <p>Dormant companions are restored from their persisted player data on
     * demand. Query tools usually complete in the same server tick. Synchronous
     * world actions complete when the normal task scheduler finishes them;
     * asynchronous actions complete with their acceptance receipt and continue
     * in the background, exactly like the existing external MCP behavior.
     */
    public static CompletableFuture<String> invoke(
            MinecraftServer server,
            UUID companionUuid,
            String toolName,
            String argsJson) {
        CompletableFuture<String> result = new CompletableFuture<>();
        if (server == null || companionUuid == null || toolName == null || toolName.isBlank()) {
            result.complete(TaskResult.fail(
                    "server, companionUuid and toolName are required").toJson());
            return result;
        }

        String toolCallId = TaskRecord.EXTERNAL_CALL_PREFIX
                + "server-" + SEQ.incrementAndGet();
        result.whenComplete((ignored, failure) -> ServerToolReplies.remove(toolCallId));

        server.execute(() -> {
            if (result.isDone()) return;

            NumenTool tool = ToolRegistry.resolve(toolName);
            if (tool == null) {
                result.complete(TaskResult.fail("unknown tool: " + toolName).toJson());
                return;
            }

            NumenPlayer body = NumenPlayer.findByUuid(server, companionUuid);
            if (body == null) body = Companions.respawn(server, companionUuid);
            if (body == null) {
                result.complete(TaskResult.fail(
                        "companion not found (never summoned, or its data is gone)").toJson());
                return;
            }

            JsonObject args;
            try {
                String raw = argsJson == null || argsJson.isBlank() ? "{}" : argsJson;
                args = JsonParser.parseString(raw).getAsJsonObject();
            } catch (RuntimeException ex) {
                result.complete(TaskResult.fail(
                        "invalid arguments JSON: " + ex.getMessage()).toJson());
                return;
            }

            try {
                ServerToolReplies.register(toolCallId, companionUuid, result::complete);
                tool.onServerCall(
                        toolCallId,
                        args,
                        body,
                        json -> ServerToolReplies.deliver(toolCallId, json));
            } catch (RuntimeException ex) {
                if (!ServerToolReplies.deliver(toolCallId,
                        TaskResult.fail("tool invocation failed: " + ex.getMessage()).toJson())) {
                    result.complete(TaskResult.fail(
                            "tool invocation failed: " + ex.getMessage()).toJson());
                }
            }
        });
        return result;
    }

    /**
     * Broadcast a companion line using Minecraft's ordinary {@code <name> text}
     * chat translation. It is intentionally a system message rather than forged
     * signed player chat, so vanilla clients can display it without chat signing
     * warnings or a client-side Numen mod.
     */
    public static CompletableFuture<Boolean> say(
            MinecraftServer server,
            UUID companionUuid,
            String message) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        String cleanMessage = message == null ? "" : message.trim();
        if (server == null || companionUuid == null
                || cleanMessage.isEmpty() || cleanMessage.length() > 256) {
            result.complete(false);
            return result;
        }

        server.execute(() -> {
            NumenPlayer body = NumenPlayer.findByUuid(server, companionUuid);
            if (body == null) body = Companions.respawn(server, companionUuid);
            if (body == null) {
                result.complete(false);
                return;
            }
            Component line = Component.translatable(
                    "chat.type.text",
                    body.getDisplayName(),
                    Component.literal(cleanMessage));
            server.getPlayerList().broadcastSystemMessage(line, false);
            result.complete(true);
        });
        return result;
    }

    private static Companion companion(
            MinecraftServer server,
            Map.Entry<UUID, CompanionRegistry.Entry> entry) {
        CompanionRegistry.Entry metadata = entry.getValue();
        return new Companion(
                entry.getKey(),
                metadata.name(),
                metadata.owner(),
                NumenPlayer.findByUuid(server, entry.getKey()) != null);
    }
}
