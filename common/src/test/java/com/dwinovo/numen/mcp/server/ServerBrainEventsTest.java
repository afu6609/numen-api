package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.api.ServerBrainAdminEvents;
import com.google.gson.Gson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerBrainEventsTest {

    @AfterEach
    void clearQueue() {
        ServerBrainEvents.clearForTests();
    }

    @Test
    void drainsChatInFifoOrder() {
        UUID player = UUID.randomUUID();
        ServerBrainEvents.publishChat(player, "Steve", "first", 10L);
        ServerBrainEvents.publishChat(player, "Steve", "second", 11L);

        List<ServerBrainEvents.Event> first = ServerBrainEvents.poll(1);
        List<ServerBrainEvents.Event> second = ServerBrainEvents.poll(16);

        assertEquals(List.of("first"), first.stream().map(ServerBrainEvents.Event::message).toList());
        assertEquals(List.of("second"), second.stream().map(ServerBrainEvents.Event::message).toList());
        assertTrue(ServerBrainEvents.poll(16).isEmpty());
    }

    @Test
    void dropsOldestChatWhenQueueIsFull() {
        UUID player = UUID.randomUUID();
        for (int i = 0; i <= ServerBrainEvents.MAX_QUEUED_EVENTS; i++) {
            ServerBrainEvents.publishChat(player, "Alex", "message-" + i, i);
        }

        List<ServerBrainEvents.Event> drained = ServerBrainEvents.poll(64);

        assertEquals(64, drained.size());
        assertEquals("message-1", drained.get(0).message());
    }

    @Test
    void trimsAndClampsPollRequests() {
        UUID player = UUID.randomUUID();
        ServerBrainEvents.publishChat(player, " Alex ", " hello ", 1L);

        ServerBrainEvents.Event event = ServerBrainEvents.poll(0).get(0);

        assertEquals("Alex", event.playerName());
        assertEquals("hello", event.message());
        assertEquals("player_chat", event.type());
    }

    @Test
    void publishesExternalTaskCompletionEnvelope() {
        UUID companion = UUID.randomUUID();
        ServerBrainEvents.publishTaskFinished(
                companion, "momo", "t42", "goto", "done", "arrived", 99L);

        ServerBrainEvents.Event event = ServerBrainEvents.poll(1).get(0);

        assertEquals("task_finished", event.type());
        assertEquals(companion, event.companionUuid());
        assertEquals("momo", event.companionName());
        assertEquals("t42", event.taskId());
        assertEquals("goto", event.taskName());
        assertEquals("done", event.status());
        assertEquals("arrived", event.message());
        assertEquals(99L, event.gameTime());
    }

    @Test
    void trustedAdminApiPublishesBoundedTestInstructionEnvelope() {
        UUID companion = UUID.randomUUID();
        ServerBrainAdminEvents.publishTestInstruction(
                companion,
                " momo ",
                " arena-42 ",
                "  clear the test zombies  ",
                new ServerBrainAdminEvents.ArenaAnchor(
                        "minecraft:overworld", 120, 72, -40),
                123L);

        ServerBrainEvents.Event event = ServerBrainEvents.poll(1).get(0);
        String json = new Gson().toJson(event);

        assertEquals("test_instruction", event.type());
        assertEquals(companion, event.companionUuid());
        assertEquals("momo", event.companionName());
        assertEquals("arena-42", event.runId());
        assertEquals("clear the test zombies", event.message());
        assertEquals(
                new ServerBrainAdminEvents.ArenaAnchor(
                        "minecraft:overworld", 120, 72, -40),
                event.arenaAnchor());
        assertEquals(Boolean.TRUE, event.freshThread());
        assertEquals(123L, event.gameTime());
        assertTrue(json.contains("\"runId\":\"arena-42\""));
        assertTrue(json.contains(
                "\"arenaAnchor\":{\"dimension\":\"minecraft:overworld\","
                        + "\"x\":120,\"y\":72,\"z\":-40}"));
        assertTrue(json.contains("\"freshThread\":true"));
        assertFalse(json.contains("playerUuid"));
    }

    @Test
    void trustedAdminApiRejectsAmbiguousOrOversizedInstructions() {
        UUID companion = UUID.randomUUID();
        ServerBrainAdminEvents.ArenaAnchor anchor =
                new ServerBrainAdminEvents.ArenaAnchor(
                        "minecraft:overworld", 0, 64, 0);

        assertThrows(
                IllegalArgumentException.class,
                () -> ServerBrainAdminEvents.publishTestInstruction(
                        companion, "momo", " ", "fight", anchor, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerBrainAdminEvents.publishTestInstruction(
                        companion,
                        "momo",
                        "run",
                        "x".repeat(
                                ServerBrainAdminEvents.MAX_INSTRUCTION_LENGTH
                                        + 1),
                        anchor,
                        0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ServerBrainAdminEvents.ArenaAnchor(
                        "overworld", 0, 64, 0));
        assertTrue(ServerBrainEvents.poll(16).isEmpty());
    }
}
