package com.dwinovo.numen.api;

import com.dwinovo.numen.mcp.server.ServerBrainEvents;

import java.util.UUID;

/**
 * Trusted server-side control seam for feeding one test objective to the
 * dedicated external brain.
 *
 * <p>This API is intended only for administrator-owned server mods such as a
 * bounded arena fixture. It is deliberately a Java publication API rather
 * than an MCP tool: the companion model can receive the resulting event, but
 * can never publish one or gain access to arena/world mutation controls.
 */
public final class ServerBrainAdminEvents {

    public static final int MAX_RUN_ID_LENGTH = 128;
    public static final int MAX_INSTRUCTION_LENGTH = 2_048;
    public static final int MAX_DIMENSION_LENGTH = 128;
    public static final int MAX_CONSOLE_SOURCE_LENGTH = 64;
    public static final int MAX_CONSOLE_MESSAGE_LENGTH = 256;
    private static final int MAX_COMPANION_NAME_LENGTH = 64;

    private ServerBrainAdminEvents() {}

    /**
     * Exact server-world reference point for a test run.
     *
     * @param dimension namespaced dimension id, for example
     *                  {@code minecraft:overworld}
     */
    public record ArenaAnchor(String dimension, int x, int y, int z) {
        public ArenaAnchor {
            dimension = requiredBounded(
                    dimension, "dimension", MAX_DIMENSION_LENGTH);
            if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException(
                        "dimension must be a namespaced resource location");
            }
        }
    }

    /**
     * Publish one test instruction, starting a fresh external-brain context.
     */
    public static void publishTestInstruction(
            UUID companionUuid,
            String companionName,
            String runId,
            String instruction,
            ArenaAnchor arenaAnchor,
            long gameTime) {
        publishTestInstruction(
                companionUuid,
                companionName,
                runId,
                instruction,
                arenaAnchor,
                true,
                gameTime);
    }

    /**
     * Publish one test instruction to the dedicated external brain.
     *
     * <p>The arena implementation must finish all privileged world setup
     * before calling this method. Publication does not grant the brain any
     * administrative action or fixture tool.
     *
     * @param freshThread {@code true} to discard the previous reasoning
     *                    context before this objective; {@code false} to
     *                    continue the current test conversation
     */
    public static void publishTestInstruction(
            UUID companionUuid,
            String companionName,
            String runId,
            String instruction,
            ArenaAnchor arenaAnchor,
            boolean freshThread,
            long gameTime) {
        if (companionUuid == null) {
            throw new IllegalArgumentException("companionUuid is required");
        }
        if (arenaAnchor == null) {
            throw new IllegalArgumentException("arenaAnchor is required");
        }
        ServerBrainEvents.publishTestInstruction(
                companionUuid,
                requiredBounded(
                        companionName,
                        "companionName",
                        MAX_COMPANION_NAME_LENGTH),
                requiredBounded(runId, "runId", MAX_RUN_ID_LENGTH),
                requiredBounded(
                        instruction,
                        "instruction",
                        MAX_INSTRUCTION_LENGTH),
                arenaAnchor,
                freshThread,
                gameTime);
    }

    /**
     * Publish one line authored by a trusted dedicated-server console.
     *
     * <p>The event carries no player UUID because a panel/RCON console has no
     * in-world player body. {@code true} means the bounded event was accepted
     * by the external-brain queue; {@code false} means the queue was occupied
     * exclusively by higher-priority control requests and the line was not
     * accepted.
     */
    public static boolean publishConsoleChat(
            String sourceName,
            String message,
            long gameTime) {
        return ServerBrainEvents.publishConsoleChat(
                requiredBounded(
                        sourceName,
                        "sourceName",
                        MAX_CONSOLE_SOURCE_LENGTH),
                requiredBounded(
                        message,
                        "message",
                        MAX_CONSOLE_MESSAGE_LENGTH),
                gameTime);
    }

    private static String requiredBounded(
            String value,
            String label,
            int maxLength) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        String clean = value.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        if (clean.length() > maxLength) {
            throw new IllegalArgumentException(
                    label + " exceeds " + maxLength + " characters");
        }
        return clean;
    }
}
